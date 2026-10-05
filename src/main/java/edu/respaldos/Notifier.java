package edu.respaldos;

import edu.respaldos.Models.Execution;

/** Recibe cada ejecucion que termina; decide por si mismo si merece un aviso. Nunca debe lanzar excepciones. */
@FunctionalInterface
public interface Notifier extends AutoCloseable {
    Notifier NONE = e -> {};

    void notifyFinished(Execution execution);

    @Override default void close() {}
}
