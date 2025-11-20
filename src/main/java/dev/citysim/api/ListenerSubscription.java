package dev.citysim.api;

/**
 * Handle for unregistering API listeners.
 */
public interface ListenerSubscription extends AutoCloseable {

    void unregister();

    boolean isActive();

    @Override
    default void close() {
        unregister();
    }
}
