package probes;

public final class JavaProbe implements Runnable {
    @Override public void run() {
        System.out.println("JAVA_PROBE_OK " + (6 * 7));
    }
}
