package edu.respaldos;

import edu.respaldos.Models.*;
import java.util.*;

/** Comparte las reglas de evidencia entre alertas y estado visual. */
public final class ExecutionEvidence {
    private ExecutionEvidence() {}

    public static List<Execution> current(List<Execution> executions, Strategy s, String coverageHash) {
        return executions.stream().filter(e -> s.id().equals(e.strategyId()) && s.databaseId().equals(e.databaseId())
            && coverageHash.equals(e.evidence().coverageHash()))
            .sorted(Comparator.comparing(Execution::startedAt).reversed()).toList();
    }

    public static boolean verified(Execution backup, List<Execution> executions) {
        var last = executions.stream().filter(e -> "VALIDATE".equals(e.operation()) && backup.id().equals(e.evidence().verifies()))
            .max(Comparator.comparing(Execution::startedAt));
        return last.map(e -> e.succeeded() && e.evidence().verified()).orElse(backup.evidence().verified());
    }

    /** Evidencia historica conservadora, no una certificacion de la cadena RMAN actual. */
    public static boolean hasVerifiedLevel0(List<Strategy> strategies, Database db, DatabaseStatus status,
                                            Strategy target, List<Execution> executions) {
        if (status == null || !status.reachable() || !db.id().equals(target.databaseId()) || !db.id().equals(status.databaseId())) return false;
        var required = RmanScript.selectedDatafiles(target, status);
        if (required.isEmpty()) return false;
        if (target.scope().equals("DATAFILE") && !required.containsAll(target.datafiles())) return false;
        if (target.scope().equals("TABLESPACE") && !status.tablespaces().containsAll(target.tablespaces())) return false;
        return strategies.stream().filter(s -> s.databaseId().equals(db.id()) && s.method().equals("LEVEL0"))
            .anyMatch(s -> current(executions, s, RmanScript.coverageHash(s, db)).stream()
                .anyMatch(e -> e.operation().equals("BACKUP") && e.succeeded() && !e.evidence().backupSets().isEmpty()
                    && !e.evidence().handles().isEmpty() && e.evidence().datafiles().containsAll(required)
                    && verified(e, executions)));
    }
}
