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
}
