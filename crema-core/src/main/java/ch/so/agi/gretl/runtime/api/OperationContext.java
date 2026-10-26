package ch.so.agi.gretl.runtime.api;

/**
 * Context supplied to one operation handler.
 *
 * <p>This is not a service locator: it offers general run services only. A
 * handler cannot read predecessor results, start other tasks or orchestrate the
 * job. It receives no Groovy type.</p>
 */
public interface OperationContext extends ContextView {
    SecretValue secret(String alias);
    String sensitive(String value);
    CancellationToken cancellation();
    TimeBudget budget();
    ResourceScope resources();
    void progress(Progress progress);
}
