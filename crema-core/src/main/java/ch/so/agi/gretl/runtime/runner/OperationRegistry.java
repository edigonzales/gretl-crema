package ch.so.agi.gretl.runtime.runner;

import ch.so.agi.gretl.i18n.Messages;
import ch.so.agi.gretl.runtime.api.OperationProvider;
import ch.so.agi.gretl.runtime.api.OperationType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Frozen catalog of complete provider definitions with origin information.
 * Loading only reads metadata; it never creates a handler or opens a domain
 * connection.
 */
public final class OperationRegistry {
    private final List<RegisteredOperation> registered;
    private final List<OperationType<?, ?>> operations;
    private final Map<String, RegisteredOperation> byId;
    private final Map<String, RegisteredOperation> bySymbol;
    private final java.util.Locale locale;

    private OperationRegistry(List<RegisteredOperation> registered, java.util.Locale locale) {
        this.locale = java.util.Objects.requireNonNull(locale, "locale");
        this.registered = List.copyOf(registered);
        this.operations = registered.stream().map(RegisteredOperation::operation).toList();
        Map<String, RegisteredOperation> ids = new LinkedHashMap<>();
        Map<String, RegisteredOperation> symbols = new LinkedHashMap<>();
        for (RegisteredOperation entry : registered) {
            OperationType<?, ?> operation = entry.operation();
            RegisteredOperation previousId = ids.putIfAbsent(operation.id(), entry);
            if (previousId != null) {
                throw new DefinitionException("DUPLICATE_OPERATION_ID",
                        Messages.format(locale, "runner.duplicateOperationId",
                                operation.id(), previousId.origin(), entry.origin()));
            }
            RegisteredOperation previousSymbol = symbols.putIfAbsent(operation.symbol(), entry);
            if (previousSymbol != null) {
                throw new DefinitionException("DUPLICATE_OPERATION_SYMBOL",
                        Messages.format(locale, "runner.duplicateOperationSymbol",
                                operation.symbol(), previousSymbol.origin(),
                                entry.origin()));
            }
        }
        this.byId = Map.copyOf(ids);
        this.bySymbol = Map.copyOf(symbols);
    }

    public static OperationRegistry load(ClassLoader classLoader) {
        return load(classLoader, java.util.Locale.GERMAN);
    }

    public static OperationRegistry load(ClassLoader classLoader, java.util.Locale locale) {
        Objects.requireNonNull(classLoader, "classLoader");
        List<OperationProvider> providers = new ArrayList<>();
        try {
            ServiceLoader.load(OperationProvider.class, classLoader).forEach(providers::add);
        } catch (java.util.ServiceConfigurationError error) {
            throw new DefinitionException("OPERATION_PROVIDER_LOAD_FAILED",
                    Messages.get(locale, "runner.providerLoadFailed"), error);
        }
        return fromProviders(providers, locale);
    }

    /** Package-visible/testable construction from already loaded providers. */
    public static OperationRegistry fromProviders(
            Collection<? extends OperationProvider> providers) {
        return fromProviders(providers, java.util.Locale.GERMAN);
    }

    public static OperationRegistry fromProviders(
            Collection<? extends OperationProvider> providers, java.util.Locale locale) {
        Objects.requireNonNull(providers, "providers");
        List<RegisteredOperation> entries = new ArrayList<>();
        for (OperationProvider provider : providers) {
            Objects.requireNonNull(provider, "provider");
            Collection<OperationType<?, ?>> operations;
            try {
                operations = provider.operations();
            } catch (RuntimeException exception) {
                throw new DefinitionException("OPERATION_PROVIDER_DEFECT",
                        Messages.format(locale, "runner.providerFailed",
                                provider.getClass().getName()), exception);
            }
            if (operations == null) {
                throw new DefinitionException("OPERATION_PROVIDER_DEFECT",
                        Messages.format(locale, "runner.providerNull",
                                provider.getClass().getName()));
            }
            for (OperationType<?, ?> operation : operations) {
                if (operation == null) {
                    throw new DefinitionException("OPERATION_PROVIDER_DEFECT",
                            Messages.format(locale, "runner.providerNullDefinition",
                                    provider.getClass().getName()));
                }
                entries.add(new RegisteredOperation(operation, provider.getClass().getName()));
            }
        }
        return new OperationRegistry(entries, locale);
    }

    public List<OperationType<?, ?>> all() {
        return operations;
    }

    public List<RegisteredOperation> registered() {
        return registered;
    }

    public OperationType<?, ?> requireId(String id) {
        RegisteredOperation entry = byId.get(id);
        if (entry == null) {
            throw new DefinitionException("UNKNOWN_OPERATION",
                    Messages.format(locale, "runner.unknownOperationId", id));
        }
        return entry.operation();
    }

    public OperationType<?, ?> requireSymbol(String symbol) {
        RegisteredOperation entry = bySymbol.get(symbol);
        if (entry == null) {
            throw new DefinitionException("UNKNOWN_OPERATION",
                    Messages.format(locale, "runner.unknownOperationSymbol", symbol));
        }
        return entry.operation();
    }

    public java.util.Optional<String> origin(OperationType<?, ?> operation) {
        RegisteredOperation entry = byId.get(operation.id());
        return entry == null ? java.util.Optional.empty()
                : java.util.Optional.of(entry.origin());
    }

    /** Definition plus the provider class that supplied it. */
    public record RegisteredOperation(OperationType<?, ?> operation, String origin) {
        public RegisteredOperation {
            Objects.requireNonNull(operation, "operation");
            if (origin == null || origin.isBlank()) {
                throw new IllegalArgumentException("origin must not be blank");
            }
        }
    }
}
