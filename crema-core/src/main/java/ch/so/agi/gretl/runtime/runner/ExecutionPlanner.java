package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.runtime.api.TaskRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Pure target selection, dependency-hull selection, cycle detection and stable
 * topological sort. It never evaluates a task body, condition or closure.
 */
public final class ExecutionPlanner {

    public ExecutionPlan plan(JobDefinition definition, List<String> requestedTargets) {
        java.util.Objects.requireNonNull(definition, "definition");
        Map<String, TaskDefinition<?>> tasks = new LinkedHashMap<>();
        for (TaskDefinition<?> task : definition.tasks()) {
            tasks.put(task.id(), task);
        }
        validateLinks(definition, tasks);

        List<PlanEdge> allDataEdges = new ArrayList<>();
        for (TaskDefinition<?> task : definition.tasks()) {
            for (TaskRef<?> predecessor : task.dependsOn()) {
                allDataEdges.add(new PlanEdge(
                        predecessor.id(), task.id(), PlanEdge.EdgeType.DEPENDS_ON));
            }
        }
        findCycle(tasks.keySet(), allDataEdges).ifPresent(cycle -> {
            throw new PlanException("DEPENDENCY_CYCLE",
                    "Zyklus im vollstaendigen dependsOn-Graph: " + renderCycle(cycle));
        });

        List<String> targets = normalizeTargets(requestedTargets, definition.defaultTargets());
        for (String target : targets) {
            if (!tasks.containsKey(target)) {
                throw new PlanException("UNKNOWN_TARGET", "Unbekanntes Ziel: " + target);
            }
        }

        Set<String> selected = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>(targets);
        while (!pending.isEmpty()) {
            String id = pending.removeFirst();
            if (!selected.add(id)) {
                continue;
            }
            TaskDefinition<?> task = tasks.get(id);
            for (TaskRef<?> predecessor : task.dependsOn()) {
                pending.addLast(predecessor.id());
            }
        }

        List<PlanEdge> selectedEdges = new ArrayList<>();
        for (TaskDefinition<?> task : definition.tasks()) {
            if (!selected.contains(task.id())) {
                continue;
            }
            for (TaskRef<?> predecessor : task.dependsOn()) {
                if (selected.contains(predecessor.id())) {
                    selectedEdges.add(new PlanEdge(
                            predecessor.id(), task.id(), PlanEdge.EdgeType.DEPENDS_ON));
                }
            }
            for (TaskRef<?> after : task.after()) {
                if (selected.contains(after.id())) {
                    selectedEdges.add(new PlanEdge(
                            after.id(), task.id(), PlanEdge.EdgeType.AFTER));
                }
            }
        }
        findCycle(selected, selectedEdges).ifPresent(cycle -> {
            throw new PlanException("PLAN_CYCLE",
                    "Zyklus im ausgewaehlten Plan: " + renderCycle(cycle));
        });

        List<TaskDefinition<?>> ordered = topologicalSort(selected, selectedEdges, tasks);
        Map<String, Set<String>> dataPredecessors = dataPredecessorSets(selected, tasks);
        return new ExecutionPlan(definition.definitionId(), List.copyOf(targets),
                ordered, dataPredecessors, selectedEdges);
    }

    private static List<String> normalizeTargets(
            List<String> requestedTargets, List<String> defaultTargets) {
        List<String> raw = requestedTargets == null || requestedTargets.isEmpty()
                ? defaultTargets : requestedTargets;
        if (raw.isEmpty()) {
            throw new PlanException("MISSING_TARGET",
                    "Keine Ziele angegeben und keine Defaults registriert");
        }
        Set<String> targets = new LinkedHashSet<>();
        for (String target : raw) {
            if (target == null || target.isBlank()) {
                throw new PlanException("INVALID_TARGET", "Target must not be blank");
            }
            targets.add(target);
        }
        return List.copyOf(targets);
    }

    private static void validateLinks(
            JobDefinition definition, Map<String, TaskDefinition<?>> tasks) {
        for (TaskDefinition<?> task : definition.tasks()) {
            for (TaskRef<?> predecessor : task.dependsOn()) {
                if (!tasks.containsKey(predecessor.id()) || !definition.containsRef(predecessor)) {
                    throw new PlanException("UNKNOWN_TASK_REFERENCE",
                            "Task " + task.id() + " has unknown predecessor: " + predecessor);
                }
            }
            for (TaskRef<?> after : task.after()) {
                if (!tasks.containsKey(after.id()) || !definition.containsRef(after)) {
                    throw new PlanException("UNKNOWN_TASK_REFERENCE",
                            "Task " + task.id() + " has unknown after reference: " + after);
                }
            }
        }
    }

    private static List<TaskDefinition<?>> topologicalSort(
            Set<String> selected, List<PlanEdge> edges, Map<String, TaskDefinition<?>> tasks) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<PlanEdge>> outgoing = new HashMap<>();
        for (String id : selected) {
            indegree.put(id, 0);
            outgoing.put(id, new ArrayList<>());
        }
        Set<String> seenEdges = new HashSet<>();
        for (PlanEdge edge : edges) {
            String key = edge.fromId() + "\u0000" + edge.toId() + "\u0000" + edge.type();
            if (!seenEdges.add(key)) {
                continue;
            }
            outgoing.get(edge.fromId()).add(edge);
            indegree.merge(edge.toId(), 1, Integer::sum);
        }
        PriorityQueue<String> ready = new PriorityQueue<>(
                Comparator.comparingInt(id -> tasks.get(id).registrationIndex()));
        indegree.forEach((id, count) -> {
            if (count == 0) {
                ready.add(id);
            }
        });
        List<TaskDefinition<?>> ordered = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            ordered.add(tasks.get(id));
            for (PlanEdge edge : outgoing.get(id)) {
                int updated = indegree.merge(edge.toId(), -1, Integer::sum);
                if (updated == 0) {
                    ready.add(edge.toId());
                }
            }
        }
        if (ordered.size() != selected.size()) {
            throw new PlanException("PLAN_CYCLE",
                    "The selected graph could not be fully sorted");
        }
        return ordered;
    }

    private static Map<String, Set<String>> dataPredecessorSets(
            Set<String> selected, Map<String, TaskDefinition<?>> tasks) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (String id : selected) {
            Set<String> predecessors = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            for (TaskRef<?> direct : tasks.get(id).dependsOn()) {
                pending.addLast(direct.id());
            }
            while (!pending.isEmpty()) {
                String predecessor = pending.removeFirst();
                if (predecessors.add(predecessor)) {
                    for (TaskRef<?> transitive : tasks.get(predecessor).dependsOn()) {
                        pending.addLast(transitive.id());
                    }
                }
            }
            result.put(id, Set.copyOf(predecessors));
        }
        return result;
    }

    /**
     * DFS cycle detection. The returned list contains the cycle edges in walk
     * direction; the last edge closes the cycle.
     */
    private static Optional<List<PlanEdge>> findCycle(Set<String> nodes, List<PlanEdge> edges) {
        Map<String, List<PlanEdge>> outgoing = new LinkedHashMap<>();
        for (String node : nodes) {
            outgoing.put(node, new ArrayList<>());
        }
        for (PlanEdge edge : edges) {
            outgoing.computeIfAbsent(edge.fromId(), key -> new ArrayList<>()).add(edge);
        }
        Map<String, Integer> color = new HashMap<>();
        Deque<PlanEdge> path = new ArrayDeque<>();
        for (String node : nodes) {
            Optional<List<PlanEdge>> found = dfs(node, outgoing, color, path);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<List<PlanEdge>> dfs(
            String node, Map<String, List<PlanEdge>> outgoing,
            Map<String, Integer> color, Deque<PlanEdge> path) {
        color.put(node, 1);
        for (PlanEdge edge : outgoing.getOrDefault(node, List.of())) {
            int targetColor = color.getOrDefault(edge.toId(), 0);
            if (targetColor == 1) {
                List<PlanEdge> cycle = new ArrayList<>();
                boolean collecting = false;
                for (PlanEdge pathEdge : path) {
                    if (pathEdge.fromId().equals(edge.toId())) {
                        collecting = true;
                    }
                    if (collecting) {
                        cycle.add(pathEdge);
                    }
                }
                cycle.add(edge);
                return Optional.of(cycle);
            }
            if (targetColor == 0) {
                path.addLast(edge);
                Optional<List<PlanEdge>> found = dfs(edge.toId(), outgoing, color, path);
                path.removeLast();
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        color.put(node, 2);
        return Optional.empty();
    }

    private static String renderCycle(List<PlanEdge> cycle) {
        if (cycle.isEmpty()) {
            return "<unknown>";
        }
        StringBuilder result = new StringBuilder();
        for (PlanEdge edge : cycle) {
            if (result.length() > 0) {
                result.append(" -> ");
            }
            result.append(edge.fromId()).append(" --").append(edge.type()).append("--> ")
                    .append(edge.toId());
        }
        return result.toString();
    }
}
