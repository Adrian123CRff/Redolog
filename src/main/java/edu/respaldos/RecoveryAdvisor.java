package edu.respaldos;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Diagnostico de recuperacion: identifica que le paso a la base (archivos borrados, instancia caida,
 * PDB cerrado, tabla eliminada, filas borradas...) y propone el paso a paso para resolverlo a mano
 * en SQL*Plus, RMAN o PowerShell. Solo lee: nunca restaura ni cambia la base; eso lo decide el DBA.
 *
 * Lo observado se guarda como lineas TAG|campo|campo, de modo que el analisis se prueba sin Oracle.
 */
public final class RecoveryAdvisor {
    public static final String PS = "PowerShell", SQL = "SQL*Plus", RMAN = "RMAN", APP = "Gestor";
    private static final Set<String> SYSTEM_TS = Set.of("SYSTEM", "UNDOTBS1", "SYSAUX");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final List<String> BASELINE = List.of("USR", "TS", "ROTAB");

    public record Step(String where, String text, String commands) {}
    public record Finding(String severity, String title, String evidence, List<Step> steps) {}
    public record Report(String checkedAt, String container, Map<String, String> summary, List<Finding> findings,
                         List<String> alertLog, Map<String, String> consoles, String savedTo) {}

    /** Lo observado en el servidor. */
    public static final class Facts {
        final String container;
        String containerState = "";
        final Map<String, List<String[]>> rows = new LinkedHashMap<>();
        final Set<String> missing = new LinkedHashSet<>();
        final List<String> alert = new ArrayList<>();
        /** Ultimos archivos conocidos (sirven cuando la instancia esta caida) y foto previa del estado. */
        Facts known = null, snapshot = null;

        public Facts(String container) { this.container = container; }

        /** Agrega lineas TAG|...; las de error ORA- se ignoran (la ausencia de filas ya lo indica). */
        public Facts parse(String text) {
            for (String line : text.split("\\R")) {
                line = line.strip();
                if (!line.matches("[A-Z_]+\\|.*")) continue;
                String[] p = line.split("\\|", -1);
                switch (p[0]) {
                    case "MISSING" -> missing.add(p[1]);
                    case "ALERT" -> alert.add(line.substring(6).strip());
                    default -> rows.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p);
                }
            }
            return this;
        }
        public Facts state(String s) { containerState = s; return this; }
        public Facts missing(String... files) { missing.addAll(List.of(files)); return this; }
        public Facts known(String text) { known = new Facts(container).parse(text); return this; }
        public Facts snapshot(String text) { snapshot = new Facts(container).parse(text); return this; }

        List<String[]> rows(String tag) { return rows.getOrDefault(tag, List.of()); }
        /** Filas que no estaban igual en la foto: lo que cambio desde entonces. */
        List<String[]> changed(String tag) {
            if (snapshot == null) return rows(tag);
            var before = new HashSet<String>();
            snapshot.rows(tag).forEach(r -> before.add(String.join("|", r)));
            return rows(tag).stream().filter(r -> !before.contains(String.join("|", r))).toList();
        }
        String value(String tag, int i) { var r = rows(tag); return r.isEmpty() || r.get(0).length <= i ? null : r.get(0)[i]; }
        boolean running() { return containerState.startsWith("running"); }
        boolean idle() { return rows("INST").isEmpty(); }
        String instance() { return idle() ? "APAGADA" : value("INST", 1); }

        /** Datos de la instancia viva o, si esta caida, los ultimos conocidos. */
        List<String[]> rowsOrKnown(String tag) { return !rows(tag).isEmpty() || known == null ? rows(tag) : known.rows(tag); }
        List<String> controlFiles() {
            String v = value("PARAM_CTL", 1);
            if (v == null && known != null) v = known.value("PARAM_CTL", 1);
            return v == null || v.isBlank() ? List.of() : List.of(v.split(",\\s*"));
        }
        String spfile() {
            String v = value("PARAM_SPF", 1);
            if ((v == null || v.isBlank()) && known != null) v = known.value("PARAM_SPF", 1);
            // Una ruta con ? o % depende del ORACLE_HOME/SID y no se puede comprobar tal cual.
            return v == null || v.isBlank() || v.contains("?") || v.contains("%") ? null : v.strip();
        }
        String dbid() {
            String v = value("DB", 3);
            if (v == null && known != null) v = known.value("DB", 3);
            if (v == null) for (var r : rows("AUTOBK")) if (r[1].matches("c-\\d+-.*")) { v = r[1].split("-")[1]; break; }
            return v == null ? "<DBID>" : v;
        }
        /** Archivos que deben existir en disco, para comprobar cuales se borraron. */
        List<String> files() {
            var list = new LinkedHashSet<String>();
            rowsOrKnown("DF").forEach(r -> list.add(r[5]));
            rowsOrKnown("LOGF").forEach(r -> list.add(r[3]));
            list.addAll(controlFiles());
            if (spfile() != null) list.add(spfile());
            return List.copyOf(list);
        }
    }

    // ---------------------------------------------------------------- recoleccion

    private static final String PROBE = """
        set pages 0 feedback off heading off lines 2000 trimspool on long 4000
        select 'INST|'||status||'|'||logins||'|'||archiver from v$instance;
        select 'NOW|'||to_char(sysdate,'YYYY-MM-DD HH24:MI:SS') from dual;
        select 'PARAM_CTL|'||value from v$parameter where name='control_files';
        select 'PARAM_SPF|'||value from v$parameter where name='spfile';
        select 'DB|'||open_mode||'|'||log_mode||'|'||dbid||'|'||current_scn from v$database;
        select 'PDB|'||name||'|'||open_mode||'|'||restricted from v$pdbs where name<>'PDB$SEED' order by con_id;
        select 'REC|'||r.file#||'|'||nvl(r.error,'-') from v$recover_file r;
        select 'DF|'||d.file#||'|'||nvl(c.name,'CDB$ROOT')||'|'||t.name||'|'||d.status||'|'||d.name
          from v$datafile d join v$tablespace t on t.ts#=d.ts# and t.con_id=d.con_id
          left join v$containers c on c.con_id=d.con_id order by d.file#;
        select 'LOG|'||group#||'|'||sequence#||'|'||status||'|'||archived from v$log order by group#;
        select 'LOGF|'||group#||'|'||nvl(status,'OK')||'|'||member from v$logfile order by group#, member;
        select 'DEST|'||dest_name||'|'||status||'|'||replace(nvl(error,'-'),'|','/') from v$archive_dest where status in ('ERROR','DISABLED','BAD PARAM') and dest_id=1;
        select 'TS|'||c.name||'|'||t.tablespace_name||'|'||t.status from cdb_tablespaces t join v$containers c on c.con_id=t.con_id
          where t.status<>'ONLINE' and c.name<>'PDB$SEED' order by c.name, t.tablespace_name;
        select 'USR|'||c.name||'|'||u.username||'|'||u.account_status from cdb_users u join v$containers c on c.con_id=u.con_id
          where u.oracle_maintained='N' and u.account_status<>'OPEN' and c.name<>'PDB$SEED' order by c.name, u.username;
        select 'ROTAB|'||c.name||'|'||t.owner||'.'||t.table_name from cdb_tables t join v$containers c on c.con_id=t.con_id
          join cdb_users u on u.username=t.owner and u.con_id=t.con_id where t.read_only='YES' and u.oracle_maintained='N' order by c.name, t.owner, t.table_name;
        select 'RMANCFG|'||name||'|'||value from v$rman_configuration where name='CONTROLFILE AUTOBACKUP';
        select 'BIN|'||c.name||'|'||b.owner||'|'||b.original_name||'|'||b.object_name||'|'||b.droptime from cdb_recyclebin b
          join v$containers c on c.con_id=b.con_id where b.type='TABLE' and b.can_undrop='YES' order by b.droptime desc fetch first 30 rows only;
        exit;
        """;

    /** Cuenta las filas de las tablas de usuario de un PDB abierto (CNT) o informa por que no se pudo leer (CNTERR). */
    static String countScript(String pdb) {
        // serveroutput se activa despues de cambiar de contenedor: el estado de DBMS_OUTPUT es propio de cada uno.
        return "set pages 0 feedback off heading off lines 2000 trimspool on\n"
            + "alter session set container=" + pdb + ";\n"
            + "set serveroutput on size unlimited\n"
            + "declare n number;\nbegin\n"
            + "  for t in (select owner, table_name from dba_tables where owner in (select username from dba_users where oracle_maintained='N')\n"
            + "            and nested='NO' and secondary='N' and temporary='N' and table_name not like 'BIN$%'\n"
            + "            and (owner, table_name) not in (select owner, table_name from dba_external_tables) order by 1,2 fetch first 300 rows only) loop\n"
            + "    begin\n"
            + "      execute immediate 'select count(*) from \"'||t.owner||'\".\"'||t.table_name||'\"' into n;\n"
            + "      dbms_output.put_line('CNT|" + pdb + "|'||t.owner||'.'||t.table_name||'|'||n);\n"
            + "    exception when others then dbms_output.put_line('CNTERR|" + pdb + "|'||t.owner||'.'||t.table_name||'|'||replace(sqlerrm,'|','/'));\n"
            + "    end;\n  end loop;\nend;\n/\nexit;\n";
    }

    private static final String SHELL = """
        for f in "$@"; do [ -e "$f" ] || echo "MISSING|$f"; done
        for b in $(ls -t /opt/oracle/backup 2>/dev/null | grep '^c-' | head -3); do echo "AUTOBK|$b"; done
        alert=$(ls -t "$ORACLE_BASE"/diag/rdbms/*/*/trace/alert_*.log 2>/dev/null | head -1)
        [ -n "$alert" ] && tail -n 400 "$alert" | grep -E 'ORA-[0-9]{5}' | tail -n 15 | sed 's/^/ALERT|/'
        exit 0
        """;

    public static Facts collect(String container, Path dir) throws Exception {
        Models.require(container != null && container.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,62}"), "Contenedor no valido.");
        Path tmp = dir.resolve("tmp");
        Files.createDirectories(tmp);
        var f = new Facts(container);
        if (Files.exists(dir.resolve("archivos.txt"))) f.known(Files.readString(dir.resolve("archivos.txt")));
        if (Files.exists(dir.resolve("foto.txt"))) f.snapshot(Files.readString(dir.resolve("foto.txt")));
        var inspect = Rman.command(List.of("docker", "inspect", "-f", "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}", container),
            "", tmp.resolve("docker.log"), Duration.ofSeconds(20));
        f.state(inspect.code() == 0 ? inspect.output().strip() : "no existe");
        if (!f.running()) return f;
        var sqlplus = List.of("docker", "exec", "-i", "-u", "oracle", container, "sqlplus", "-s", "/", "as", "sysdba");
        f.parse(Rman.command(sqlplus, PROBE, tmp.resolve("sql.log"), Duration.ofSeconds(60)).output());
        for (var pdb : f.rows("PDB"))
            if (pdb[2].startsWith("READ") && pdb[1].matches("[A-Z0-9_$#]{1,30}"))
                f.parse(Rman.command(sqlplus, countScript(pdb[1]), tmp.resolve("cnt-" + pdb[1] + ".log"), Duration.ofSeconds(60)).output());
        // El script va por la entrada estandar: en Windows, las comillas de un argumento largo no llegan intactas a docker.
        var args = new ArrayList<>(List.of("docker", "exec", "-i", "-u", "oracle", container, "sh", "-s", "--"));
        args.addAll(f.files());
        f.parse(Rman.command(args, SHELL, tmp.resolve("sh.log"), Duration.ofSeconds(30)).output());
        // Con la base montada se conocen sus archivos: se guardan para diagnosticar aunque luego se caiga.
        if (!f.rows("DF").isEmpty()) {
            var known = new StringBuilder("# Archivos de la base conocidos en el ultimo diagnostico\n");
            for (String tag : List.of("PARAM_CTL", "PARAM_SPF", "DB", "DF", "LOG", "LOGF"))
                for (var r : f.rows(tag)) known.append(String.join("|", r)).append('\n');
            Files.writeString(dir.resolve("archivos.txt"), known, StandardCharsets.UTF_8);
        }
        return f;
    }

    /** Guarda la cantidad de filas de cada tabla y el SCN: la referencia para saber si se perdieron datos. */
    public static Map<String, Object> snapshot(String container, Path dir) throws Exception {
        var f = collect(container, dir);
        Models.require(f.running() && !f.idle() && "READ WRITE".equals(f.value("DB", 1)), "La base debe estar abierta para tomar la foto del estado.");
        var text = new StringBuilder("# Foto del estado de la base: filas por tabla antes de cualquier fallo\n");
        text.append("TOMADA|").append(f.value("NOW", 1)).append('\n');
        text.append("SCN|").append(f.value("DB", 4)).append('\n');
        text.append("DBID|").append(f.value("DB", 3)).append('\n');
        long total = 0;
        for (var r : f.rows("CNT")) { text.append(String.join("|", r)).append('\n'); total += Long.parseLong(r[3]); }
        // Lo que ya estaba asi antes (usuarios de prueba bloqueados, tablespaces de solo lectura) no se informa como fallo.
        for (String tag : BASELINE) for (var r : f.rows(tag)) text.append(String.join("|", r)).append('\n');
        Files.writeString(dir.resolve("foto.txt"), text, StandardCharsets.UTF_8);
        return Map.of("takenAt", f.value("NOW", 1), "scn", f.value("DB", 4), "tables", f.rows("CNT").size(), "rows", total);
    }

    public static Report diagnose(String container, Path dir) throws Exception {
        var f = collect(container, dir);
        var findings = analyze(f);
        var summary = new LinkedHashMap<String, String>();
        summary.put("Contenedor", f.containerState);
        if (f.running()) {
            summary.put("Instancia", f.instance());
            if (f.value("DB", 1) != null) summary.put("Base", f.value("DB", 1) + " · " + f.value("DB", 2));
            if (!f.rows("PDB").isEmpty()) summary.put("PDBs", String.join(", ", f.rows("PDB").stream().map(p -> p[1] + " (" + p[2] + ")").toList()));
            summary.put("Archivos revisados", f.files().size() + (f.idle() && f.known != null ? " (lista del ultimo diagnostico)" : "") + ", faltan " + f.missing.size());
        }
        summary.put("Foto del estado", f.snapshot == null ? "Sin foto: no se pueden comparar filas" : f.snapshot.value("TOMADA", 1) + " (reloj de Oracle, UTC) · SCN " + f.snapshot.value("SCN", 1) + " · " + f.snapshot.rows("CNT").size() + " tablas");
        var consoles = Map.of(SQL, "docker exec -it -u oracle " + container + " sqlplus / as sysdba",
            RMAN, "docker exec -it -u oracle " + container + " rman target /");
        String checked = LocalDateTime.now(BackupService.ZONE_ID).withNano(0).toString();
        Path file = dir.resolve("diagnostico-" + LocalDateTime.now(BackupService.ZONE_ID).format(STAMP) + ".txt");
        var report = new Report(checked, container, summary, findings, f.alert, consoles, file.toString());
        Files.writeString(file, text(report), StandardCharsets.UTF_8);
        return report;
    }

    /** El informe como texto plano: se guarda como evidencia y se puede descargar. */
    public static String text(Report r) {
        var s = new StringBuilder("DIAGNOSTICO DE RECUPERACION - " + r.container() + " - " + r.checkedAt() + "\n");
        s.append("Solo lectura: no se restauro ni se cambio nada.\n\n");
        r.summary().forEach((k, v) -> s.append(k).append(": ").append(v).append('\n'));
        s.append("\nSQL*Plus: ").append(r.consoles().get(SQL)).append("\nRMAN:     ").append(r.consoles().get(RMAN)).append('\n');
        int i = 1;
        for (var f : r.findings()) {
            s.append("\n").append(i++).append(". [").append(f.severity()).append("] ").append(f.title()).append('\n');
            if (!f.evidence().isBlank()) s.append("   Evidencia: ").append(f.evidence().replace("\n", "\n              ")).append('\n');
            int n = 1;
            for (var st : f.steps()) {
                s.append("   ").append(n++).append(") ").append(st.where()).append(": ").append(st.text()).append('\n');
                if (!st.commands().isBlank()) s.append("      ").append(st.commands().replace("\n", "\n      ")).append('\n');
            }
        }
        if (!r.alertLog().isEmpty()) s.append("\nUltimos errores del alert log:\n  ").append(String.join("\n  ", r.alertLog())).append('\n');
        return s.toString();
    }

    // ---------------------------------------------------------------- analisis

    private static Step step(String where, String text, String commands) { return new Step(where, text, commands); }
    private static Step step(String where, String text) { return new Step(where, text, ""); }

    public static List<Finding> analyze(Facts f) {
        var out = new ArrayList<Finding>();
        String c = f.container;
        if (!f.running()) {
            out.add(new Finding("CRITICO", "El contenedor " + c + " no esta en ejecucion", "Estado en Docker: " + f.containerState, List.of(
                step(PS, "Iniciar el contenedor y esperar a que Oracle quede healthy (1 a 3 minutos).", "docker start " + c + "\ndocker inspect -f \"{{.State.Health.Status}}\" " + c),
                step(APP, "Cuando diga healthy, volver a pulsar Diagnosticar fallos."))));
            return out;
        }
        String inst = f.instance(), dbid = f.dbid();
        boolean idle = f.idle(), open = "OPEN".equals(inst), mounted = open || "MOUNTED".equals(inst);
        String autobackup = "SET CONTROLFILE AUTOBACKUP FORMAT FOR DEVICE TYPE DISK TO '" + Models.DEFAULT_DESTINATION + "/%F';";
        boolean resetlogs = false;
        var handledPdbs = new HashSet<String>();
        var handledTs = new HashSet<String>();

        // 1. SPFILE
        String spf = f.spfile();
        if (spf != null && f.missing.contains(spf)) {
            if (!idle) out.add(new Finding("MEDIO", "Se borro el SPFILE (la instancia sigue funcionando con los parametros en memoria)", "Falta " + spf, List.of(
                step(SQL, "Recrear el SPFILE desde la memoria de la instancia. Sin perdida de datos.", "CREATE SPFILE FROM MEMORY;"))));
            else out.add(new Finding("CRITICO", "Falta el SPFILE: la instancia no puede arrancar", "Falta " + spf, List.of(
                step(RMAN, "Arrancar con parametros minimos y restaurar el SPFILE desde el autobackup.",
                    "STARTUP FORCE NOMOUNT;\nSET DBID " + dbid + ";\nRUN {\n  " + autobackup + "\n  RESTORE SPFILE FROM AUTOBACKUP;\n}\nSTARTUP FORCE;"),
                step(APP, "Volver a pulsar Diagnosticar fallos: si faltaba algo mas, ahora se ve."))));
        }

        // 2. Control files
        var ctl = f.controlFiles();
        var lostCtl = ctl.stream().filter(f.missing::contains).toList();
        if (!lostCtl.isEmpty() && lostCtl.size() < ctl.size()) {
            String good = ctl.stream().filter(x -> !f.missing.contains(x)).findFirst().orElseThrow();
            var copies = new StringBuilder();
            for (String lost : lostCtl) copies.append("docker exec -u oracle ").append(c).append(" cp ").append(good).append(' ').append(lost).append('\n');
            var steps = new ArrayList<Step>();
            if (!idle) steps.add(step(SQL, "Detener la instancia (con una copia del control file perdida va a caerse igual).", "SHUTDOWN ABORT;"));
            steps.add(step(PS, "Copiar la copia sana sobre la que falta.", copies.toString().strip()));
            steps.add(step(SQL, "Arrancar y abrir los PDB. Sin perdida de datos.", "STARTUP;\nALTER PLUGGABLE DATABASE ALL OPEN;"));
            out.add(new Finding("ALTO", "Se perdio una copia del control file (queda otra sana)", "Falta " + String.join(", ", lostCtl) + "\nSana: " + good, steps));
        } else if (!lostCtl.isEmpty()) {
            resetlogs = true;
            var steps = new ArrayList<Step>();
            if (!idle) steps.add(step(SQL, "Detener la instancia.", "SHUTDOWN ABORT;"));
            steps.add(step(RMAN, "Restaurar el control file desde el autobackup y recuperar la base (aplica archived logs y redo en linea).",
                "STARTUP NOMOUNT;\nSET DBID " + dbid + ";\nRUN {\n  " + autobackup + "\n  RESTORE CONTROLFILE FROM AUTOBACKUP;\n}\nALTER DATABASE MOUNT;\nRECOVER DATABASE;\nALTER DATABASE OPEN RESETLOGS;\nALTER PLUGGABLE DATABASE ALL OPEN;"));
            out.add(new Finding("CRITICO", "Se perdieron todos los control files", "Faltan " + String.join(", ", lostCtl)
                + "\nSi RECOVER se detiene pidiendo un log, en SQL*Plus: RECOVER DATABASE USING BACKUP CONTROLFILE UNTIL CANCEL; e indicar los redo en linea.", steps));
        } else if ("STARTED".equals(inst)) {
            out.add(new Finding("ALTO", "La base quedo en NOMOUNT (iniciada, sin montar)", "Los control files existen.", List.of(
                step(SQL, "Montar y abrir.", "ALTER DATABASE MOUNT;\nALTER DATABASE OPEN;\nALTER PLUGGABLE DATABASE ALL OPEN;"),
                step(APP, "Si alguno da error (ORA-01157, ORA-00313...), volver a pulsar Diagnosticar fallos."))));
        }

        // 3. Datafiles: borrados del disco o que piden recuperacion
        var recover = new HashMap<String, String>();
        f.rows("REC").forEach(r -> recover.put(r[1], r[2]));
        var byTs = new LinkedHashMap<String, List<String[]>>();
        for (var d : f.rowsOrKnown("DF"))
            if (f.missing.contains(d[5]) || recover.containsKey(d[1]) || (!idle && List.of("OFFLINE", "RECOVER").contains(d[4])))
                byTs.computeIfAbsent(d[2] + ":" + d[3], k -> new ArrayList<>()).add(d);
        var rootSystem = new ArrayList<String[]>();
        for (var e : byTs.entrySet()) {
            String con = e.getValue().get(0)[2], ts = e.getValue().get(0)[3];
            if (con.equals("CDB$ROOT") && SYSTEM_TS.contains(ts)) rootSystem.addAll(e.getValue());
        }
        if (!rootSystem.isEmpty()) {
            String nums = String.join(", ", rootSystem.stream().map(d -> d[1]).toList());
            var steps = new ArrayList<Step>();
            if (open) steps.add(step(SQL, "Detener y montar la base (sin SYSTEM/UNDO el contenedor raiz no puede quedar abierto).", "SHUTDOWN ABORT;\nSTARTUP MOUNT;"));
            else if (!mounted) steps.add(step(SQL, "Montar la base.", idle ? "STARTUP MOUNT;" : "ALTER DATABASE MOUNT;"));
            steps.add(step(RMAN, "Restaurar y recuperar los datafiles; recupera hasta el ultimo commit.", "RESTORE DATAFILE " + nums + ";\nRECOVER DATAFILE " + nums + ";\nALTER DATABASE OPEN;\nALTER PLUGGABLE DATABASE ALL OPEN;"));
            out.add(new Finding("CRITICO", "Se perdio un datafile del sistema del contenedor raiz (" + String.join(", ", rootSystem.stream().map(d -> d[3]).distinct().toList()) + ")", evidence(rootSystem, f, recover), steps));
        }
        for (var e : byTs.entrySet()) {
            var files = e.getValue();
            String con = files.get(0)[2], ts = files.get(0)[3];
            if (con.equals("CDB$ROOT") && SYSTEM_TS.contains(ts)) continue;
            String pdbMode = f.rows("PDB").stream().filter(p -> p[1].equals(con)).map(p -> p[2]).findFirst().orElse("");
            var steps = new ArrayList<Step>();
            if (rootSystem.isEmpty() && !open) steps.add(step(SQL, "Abrir el contenedor raiz.", idle ? "STARTUP;" : mounted ? "ALTER DATABASE OPEN;" : "ALTER DATABASE MOUNT;\nALTER DATABASE OPEN;"));
            if (!con.equals("CDB$ROOT") && SYSTEM_TS.contains(ts)) {
                if (!handledPdbs.add(con)) continue;
                steps.add(step(RMAN, "Restaurar y recuperar el PDB completo (si dice que ya esta cerrado, seguir con la siguiente linea).",
                    "ALTER PLUGGABLE DATABASE " + con + " CLOSE ABORT;\nRESTORE PLUGGABLE DATABASE " + con + ";\nRECOVER PLUGGABLE DATABASE " + con + ";\nALTER PLUGGABLE DATABASE " + con + " OPEN;"));
                out.add(new Finding("ALTO", "Se perdieron archivos del sistema del PDB " + con + " (" + ts + ")", evidence(files, f, recover), steps));
                continue;
            }
            handledTs.add(con + ":" + ts);
            boolean root = con.equals("CDB$ROOT");
            String target = root ? ts : con + ":" + ts, session = root ? "" : "ALTER SESSION SET CONTAINER = " + con + ";\n";
            if (root || pdbMode.startsWith("READ")) {
                steps.add(step(SQL, "Poner el tablespace fuera de linea (el resto de la base sigue funcionando).", session + "ALTER TABLESPACE " + ts + " OFFLINE IMMEDIATE;"));
                steps.add(step(RMAN, "Restaurar y recuperar; aplica los incrementales y los archived logs hasta el ultimo commit.", "RESTORE TABLESPACE " + target + ";\nRECOVER TABLESPACE " + target + ";"));
                steps.add(step(SQL, "Volver a ponerlo en linea y comprobar.", session + "ALTER TABLESPACE " + ts + " ONLINE;\nSELECT file#, error FROM v$recover_file;"));
            } else {
                steps.add(step(RMAN, "El PDB esta cerrado: restaurar, recuperar y abrirlo.", "RESTORE TABLESPACE " + target + ";\nRECOVER TABLESPACE " + target + ";\nALTER PLUGGABLE DATABASE " + con + " OPEN;"));
                handledPdbs.add(con);
            }
            out.add(new Finding("ALTO", "Se perdio el datafile del tablespace " + target, evidence(files, f, recover), steps));
        }

        // 4. Redo en linea
        var members = new LinkedHashMap<String, List<String[]>>();
        for (var m : f.rowsOrKnown("LOGF")) members.computeIfAbsent(m[1], k -> new ArrayList<>()).add(m);
        var groups = new HashMap<String, String[]>();
        for (var g : f.rowsOrKnown("LOG")) groups.put(g[1], g);
        for (var e : members.entrySet()) {
            String g = e.getKey();
            var lost = e.getValue().stream().filter(m -> f.missing.contains(m[3]) || "INVALID".equals(m[2])).map(m -> m[3]).toList();
            if (lost.isEmpty()) continue;
            var info = groups.get(g);
            String status = idle ? "desconocido (instancia apagada)" : info == null ? "?" : info[3];
            String seq = idle || info == null ? "<secuencia del grupo " + g + " segun el SELECT anterior>" : info[2];
            String ev = "Grupo " + g + " (" + status + ", secuencia " + seq + "): falta " + String.join(", ", lost);
            if (lost.size() < e.getValue().size()) {
                var steps = new ArrayList<Step>();
                if (idle) steps.add(step(SQL, "Arrancar: Oracle abre con el miembro que queda.", "STARTUP;"));
                // Solo el grupo CURRENT necesita el cambio de log y el ACTIVE el checkpoint: cambiar de log con el grupo
                // inactivo podria convertirlo justo en el actual, y de ese no se pueden quitar miembros (ORA-01609).
                var sql = new StringBuilder(idle ? "SELECT group#, status FROM v$log;   -- si el grupo " + g + " es CURRENT: ALTER SYSTEM SWITCH LOGFILE; y ALTER SYSTEM CHECKPOINT;\n"
                    : "CURRENT".equals(status) ? "ALTER SYSTEM SWITCH LOGFILE;\nALTER SYSTEM CHECKPOINT;\n" : "ACTIVE".equals(status) ? "ALTER SYSTEM CHECKPOINT;\n" : "");
                for (String m : lost) sql.append("ALTER DATABASE DROP LOGFILE MEMBER '").append(m).append("';\nALTER DATABASE ADD LOGFILE MEMBER '").append(m).append("' TO GROUP ").append(g).append(";\n");
                sql.append("SELECT group#, member, status FROM v$logfile ORDER BY 1;");
                steps.add(step(SQL, "Sacar el grupo de uso y recrear el miembro perdido. Sin perdida de datos: el redo multiplexado siguio escribiendo en la copia.", sql.toString()));
                out.add(new Finding("BAJO", "Se perdio un miembro del redo del grupo " + g + " (queda la copia)", ev, steps));
            } else if (!idle && "INACTIVE".equals(status)) {
                boolean archived = "YES".equals(info[4]);
                var steps = new ArrayList<Step>();
                steps.add(step(SQL, archived ? "El grupo ya estaba archivado: limpiarlo lo recrea. Sin perdida de datos." : "El grupo no estaba archivado: limpiarlo corta la cadena de archived logs.",
                    "ALTER DATABASE CLEAR " + (archived ? "" : "UNARCHIVED ") + "LOGFILE GROUP " + g + ";" + (open ? "" : "\nALTER DATABASE OPEN;\nALTER PLUGGABLE DATABASE ALL OPEN;")));
                if (!archived) steps.add(step(APP, "Ejecutar de inmediato la estrategia de base completa (nivel 0): los respaldos anteriores ya no llegan hasta hoy."));
                out.add(new Finding(archived ? "MEDIO" : "ALTO", "Se perdio el grupo de redo " + g + " completo (inactivo)", ev, steps));
            } else if (!idle && "ACTIVE".equals(status)) {
                out.add(new Finding("ALTO", "Se perdio el grupo de redo " + g + " completo (activo)", ev, List.of(
                    step(SQL, "Forzar un checkpoint: el grupo pasa a INACTIVE y se puede limpiar sin perdida.", "ALTER SYSTEM CHECKPOINT;\nSELECT group#, status, archived FROM v$log;\nALTER DATABASE CLEAR LOGFILE GROUP " + g + ";"),
                    step(APP, "Si el CHECKPOINT falla y la instancia se cae, volver a pulsar Diagnosticar fallos (sera recuperacion incompleta)."))));
            } else {
                resetlogs = true;
                var steps = new ArrayList<Step>();
                steps.add(step(SQL, "Montar la base y confirmar la secuencia del grupo perdido.", (idle ? "" : "SHUTDOWN ABORT;\n") + "STARTUP MOUNT;\nSELECT group#, sequence#, status FROM v$log;"));
                steps.add(step(RMAN, "Recuperacion incompleta hasta antes de esa secuencia (lo que solo estaba en ese grupo se pierde).",
                    "RUN {\n  SET UNTIL SEQUENCE " + seq + " THREAD 1;\n  RESTORE DATABASE;\n  RECOVER DATABASE;\n}\nALTER DATABASE OPEN RESETLOGS;\nALTER PLUGGABLE DATABASE ALL OPEN;"));
                out.add(new Finding("CRITICO", "Se perdio el grupo de redo " + g + " completo" + (idle ? "" : " (en uso)"), ev
                    + "\nEquivalente en SQL*Plus: RECOVER DATABASE UNTIL CANCEL; aplicando los archived logs y escribiendo CANCEL.", steps));
            }
        }

        // 5. Estado de la instancia, base y PDB
        if (idle && out.isEmpty()) {
            out.add(new Finding("CRITICO", "La instancia esta apagada", f.alert.isEmpty() ? "No se encontraron archivos faltantes." : "Ultimo error del alert log: " + f.alert.get(f.alert.size() - 1), List.of(
                step(SQL, "Arrancarla y abrir los PDB.", "STARTUP;\nALTER PLUGGABLE DATABASE ALL OPEN;"),
                step(APP, "Si STARTUP se detiene con un error, volver a pulsar Diagnosticar fallos: con la base en NOMOUNT o MOUNT el diagnostico es exacto."))));
        } else if (idle) {
            out.add(0, new Finding("INFO", "La instancia esta apagada", "Los hallazgos se dedujeron de los archivos que faltan en disco (lista del ultimo diagnostico con la base montada). "
                + "Si al seguir los pasos aparece otro error, volver a pulsar Diagnosticar fallos.", List.of()));
        }
        if (out.isEmpty() && "MOUNTED".equals(inst))
            out.add(new Finding("ALTO", "La base esta montada pero cerrada", "", List.of(
                step(SQL, "Abrirla.", "ALTER DATABASE OPEN;\nALTER PLUGGABLE DATABASE ALL OPEN;"),
                step(APP, "Si responde ORA-01589, venia de una recuperacion incompleta: ALTER DATABASE OPEN RESETLOGS;. Con otro error, volver a diagnosticar."))));
        if (open && "READ ONLY".equals(f.value("DB", 1)))
            out.add(new Finding("MEDIO", "La base esta abierta en solo lectura", "", List.of(step(SQL, "Reiniciarla en lectura y escritura.", "SHUTDOWN IMMEDIATE;\nSTARTUP;\nALTER PLUGGABLE DATABASE ALL OPEN;"))));
        if (!idle && "RESTRICTED".equals(f.value("INST", 2)))
            out.add(new Finding("MEDIO", "La instancia esta en modo restringido (solo administradores pueden conectarse)", "", List.of(step(SQL, "Quitar la restriccion.", "ALTER SYSTEM DISABLE RESTRICTED SESSION;"))));
        if (open) for (var p : f.rows("PDB")) {
            if (handledPdbs.contains(p[1])) continue;
            if (p[2].equals("MOUNTED"))
                out.add(new Finding("ALTO", "El PDB " + p[1] + " esta cerrado", "Estado: MOUNTED", List.of(
                    step(SQL, "Abrirlo y guardar el estado para que abra solo al reiniciar.", "ALTER PLUGGABLE DATABASE " + p[1] + " OPEN;\nALTER PLUGGABLE DATABASE " + p[1] + " SAVE STATE;"),
                    step(APP, "Si responde ORA-01157 u ORA-01110 (falta un archivo), volver a pulsar Diagnosticar fallos."))));
            else if (p[2].equals("READ ONLY"))
                out.add(new Finding("MEDIO", "El PDB " + p[1] + " esta en solo lectura", "", List.of(step(SQL, "Reabrirlo en lectura y escritura.", "ALTER PLUGGABLE DATABASE " + p[1] + " CLOSE IMMEDIATE;\nALTER PLUGGABLE DATABASE " + p[1] + " OPEN;"))));
            else if ("YES".equals(p[3]))
                out.add(new Finding("MEDIO", "El PDB " + p[1] + " esta abierto en modo restringido", "", List.of(step(SQL, "Reabrirlo sin restriccion.", "ALTER PLUGGABLE DATABASE " + p[1] + " CLOSE IMMEDIATE;\nALTER PLUGGABLE DATABASE " + p[1] + " OPEN;"))));
        }

        // 6. Configuracion que el fallo pudo haber desactivado
        if (mounted && "NOARCHIVELOG".equals(f.value("DB", 2)))
            out.add(new Finding("ALTO", "La base esta en NOARCHIVELOG: no se pueden recuperar cambios posteriores al ultimo respaldo", "", List.of(
                step(SQL, "Volver a ARCHIVELOG (requiere reiniciar).", "SHUTDOWN IMMEDIATE;\nSTARTUP MOUNT;\nALTER DATABASE ARCHIVELOG;\nALTER DATABASE OPEN;\nALTER PLUGGABLE DATABASE ALL OPEN;\nARCHIVE LOG LIST;"),
                step(APP, "Ejecutar la estrategia de base completa (nivel 0) para iniciar una cadena sana."))));
        if (!idle && (!f.rows("DEST").isEmpty() || List.of("STOPPED", "FAILED").contains(f.value("INST", 3)))) {
            String ev = f.rows("DEST").isEmpty() ? "Archiver: " + f.value("INST", 3) : String.join("\n", f.rows("DEST").stream().map(d -> d[1] + " " + d[2] + ": " + d[3]).toList());
            out.add(new Finding("ALTO", "El archivado de redo esta detenido o con error", ev, List.of(
                step(PS, "Comprobar que la carpeta exista y tenga espacio.", "docker exec -u oracle " + c + " mkdir -p /opt/oracle/oradata/FREE/archivelog\ndocker exec -u oracle " + c + " df -h /opt/oracle/oradata"),
                step(SQL, "Reactivar el destino y archivar.", "ALTER SYSTEM SET log_archive_dest_1='LOCATION=/opt/oracle/oradata/FREE/archivelog' SCOPE=BOTH;\nALTER SYSTEM SET log_archive_dest_state_1=ENABLE SCOPE=BOTH;\nALTER SYSTEM ARCHIVE LOG CURRENT;"))));
        }
        for (var t : f.changed("TS")) {
            if (handledTs.contains(t[1] + ":" + t[2]) || handledPdbs.contains(t[1])) continue;
            String session = t[1].equals("CDB$ROOT") ? "" : "ALTER SESSION SET CONTAINER = " + t[1] + ";\n", target = t[1].equals("CDB$ROOT") ? t[2] : t[1] + ":" + t[2];
            if (t[3].equals("OFFLINE"))
                out.add(new Finding("ALTO", "El tablespace " + target + " esta fuera de linea", "Sus tablas no se pueden consultar.", List.of(
                    step(SQL, "Ponerlo en linea.", session + "ALTER TABLESPACE " + t[2] + " ONLINE;"),
                    step(RMAN, "Solo si responde ORA-01113 (necesita recuperacion): recuperarlo y repetir el paso anterior.", "RECOVER TABLESPACE " + target + ";"))));
            else if (t[3].equals("READ ONLY"))
                out.add(new Finding("MEDIO", "El tablespace " + target + " esta en solo lectura", "Si no debia estarlo:", List.of(step(SQL, "Habilitar escritura.", session + "ALTER TABLESPACE " + t[2] + " READ WRITE;"))));
        }
        for (var t : f.changed("ROTAB")) {
            String session = t[1].equals("CDB$ROOT") ? "" : "ALTER SESSION SET CONTAINER = " + t[1] + ";\n";
            out.add(new Finding("MEDIO", "La tabla " + t[2] + " esta en solo lectura", "Las inserciones y cambios fallan con ORA-12081.", List.of(
                step(SQL, "Habilitar escritura (si no debia estar en solo lectura).", session + "ALTER TABLE " + t[2] + " READ WRITE;"))));
        }
        if (f.rows("RMANCFG").stream().anyMatch(r -> r[2].strip().equalsIgnoreCase("OFF")))
            out.add(new Finding("MEDIO", "El autobackup del control file esta desactivado", "Sin el, perder los control files obliga a buscar una pieza a mano.", List.of(
                step(RMAN, "Reactivarlo y dejar una copia ya.", "CONFIGURE CONTROLFILE AUTOBACKUP ON;\nBACKUP CURRENT CONTROLFILE;"))));
        var users = f.changed("USR");
        if (!users.isEmpty()) {
            var sql = new StringBuilder();
            String last = null;
            for (var u : users) {
                if (!u[1].equals(last) && !u[1].equals("CDB$ROOT")) sql.append("ALTER SESSION SET CONTAINER = ").append(u[1]).append(";\n");
                last = u[1];
                if (u[3].contains("LOCKED")) sql.append("ALTER USER ").append(u[2]).append(" ACCOUNT UNLOCK;\n");
                if (u[3].contains("EXPIRED")) sql.append("ALTER USER ").append(u[2]).append(" IDENTIFIED BY \"<nueva_contrasena>\";\n");
            }
            out.add(new Finding("MEDIO", "Hay usuarios bloqueados o con la contrasena vencida", String.join("\n", users.stream().map(u -> u[1] + ": " + u[2] + " (" + u[3] + ")").toList()), List.of(
                step(SQL, "Desbloquearlos (la contrasena nueva la escribe el DBA).", sql.toString().strip()))));
        }

        // 7. Errores logicos: tablas eliminadas y filas que faltan respecto de la foto
        var snap = f.snapshot;
        String since = snap == null ? null : snap.value("TOMADA", 1);
        var counts = new HashMap<String, Long>();
        f.rows("CNT").forEach(r -> counts.put(r[1] + ":" + r[2], Long.parseLong(r[3])));
        var countedPdbs = new HashSet<String>();
        f.rows("CNT").forEach(r -> countedPdbs.add(r[1]));
        f.rows("CNTERR").forEach(r -> countedPdbs.add(r[1]));
        var dropped = new LinkedHashSet<String>();
        String recent = f.value("NOW", 1) == null ? null : LocalDateTime.parse(f.value("NOW", 1).replace(' ', 'T')).minusDays(1).toString().replace('T', ' ');
        for (var b : f.rows("BIN")) {
            String when = b[5].replaceFirst(":", " ");
            if (since != null ? when.compareTo(since) < 0 : recent == null || when.compareTo(recent) < 0) continue;
            String table = b[2] + "." + b[3];
            if (!dropped.add(b[1] + ":" + table)) continue;
            boolean exists = counts.containsKey(b[1] + ":" + table);
            String session = b[1].equals("CDB$ROOT") ? "" : "ALTER SESSION SET CONTAINER = " + b[1] + ";\n";
            out.add(new Finding("ALTO", "Se elimino la tabla " + table + " (esta en la papelera)", "Eliminada " + b[5] + " · objeto " + b[4]
                + (snap != null && snap.rows("CNT").stream().anyMatch(r -> (r[1] + ":" + r[2]).equals(b[1] + ":" + table)) ? "\nFilas en la foto: " + snapCount(snap, b[1], table) : ""), List.of(
                step(SQL, "Recuperarla desde la papelera" + (exists ? " (ya existe otra con ese nombre: se recupera con otro nombre)" : "") + ".",
                    session + "FLASHBACK TABLE " + b[2] + ".\"" + b[3] + "\" TO BEFORE DROP" + (exists ? " RENAME TO " + b[3] + "_RECUPERADA" : "") + ";\nSELECT COUNT(*) FROM " + b[2] + "." + (exists ? b[3] + "_RECUPERADA" : b[3]) + ";"))));
        }
        if (snap != null) for (var r : snap.rows("CNT")) {
            String pdb = r[1], table = r[2];
            long before = Long.parseLong(r[3]);
            if (!countedPdbs.contains(pdb) || dropped.contains(pdb + ":" + table) || handledPdbs.contains(pdb)) continue;
            Long now = counts.get(pdb + ":" + table);
            // El SCN de la foto es exacto; la hora se convierte a SCN con algunos segundos de margen.
            String scn = snap.value("SCN", 1), owner = table.split("\\.")[0], name = table.split("\\.")[1];
            boolean bySc = scn != null && scn.matches("\\d+");
            String ts = bySc ? "SCN " + scn : "TIMESTAMP TO_TIMESTAMP('" + since + "','YYYY-MM-DD HH24:MI:SS')";
            Step recoverTable = step(RMAN, "Si lo anterior no funciona (ORA-01466 por TRUNCATE, cambio de estructura o tabla recien creada, u ORA-01555 por falta de undo): recuperar la tabla de los respaldos con otro nombre. Tarda unos minutos y usa espacio temporal.",
                "RECOVER TABLE " + table + " OF PLUGGABLE DATABASE " + pdb + "\n  UNTIL " + (bySc ? "SCN " + scn : "TIME \"TO_DATE('" + since + "','YYYY-MM-DD HH24:MI:SS')\"")
                    + "\n  AUXILIARY DESTINATION '/opt/oracle/oradata/aux'\n  REMAP TABLE " + table + ":" + name + "_RECUPERADA;");
            Step aux = step(PS, "Crear la carpeta auxiliar para RMAN.", "docker exec -u oracle " + c + " mkdir -p /opt/oracle/oradata/aux");
            if (now == null) {
                if (f.rows("CNTERR").stream().anyMatch(e -> (e[1] + ":" + e[2]).equals(pdb + ":" + table))) continue;
                out.add(new Finding("ALTO", "La tabla " + table + " ya no existe y no esta en la papelera (DROP ... PURGE)", "En la foto (" + since + ") tenia " + before + " filas.", List.of(aux, recoverTable,
                    step(SQL, "Devolverle el nombre original.", "ALTER SESSION SET CONTAINER = " + pdb + ";\nALTER TABLE " + owner + "." + name + "_RECUPERADA RENAME TO " + name + ";\nSELECT COUNT(*) FROM " + table + ";"))));
            } else if (now < before) {
                out.add(new Finding("ALTO", "Faltan filas en " + table + " (antes " + before + ", ahora " + now + ")", "Foto tomada " + since + ".", List.of(
                    step(SQL, "Ver cuantas filas habia en ese momento (consulta flashback con el undo) y reinsertar las que faltan.",
                        "ALTER SESSION SET CONTAINER = " + pdb + ";\nSELECT COUNT(*) FROM " + table + " AS OF " + ts + ";\nINSERT INTO " + table + "\n  SELECT * FROM " + table + " AS OF " + ts + "\n  MINUS SELECT * FROM " + table + ";\nCOMMIT;\nSELECT COUNT(*) FROM " + table + ";"),
                    aux, recoverTable,
                    step(SQL, "Si se uso RECOVER TABLE: copiar las filas que faltan desde la tabla recuperada.", "ALTER SESSION SET CONTAINER = " + pdb + ";\nINSERT INTO " + table + " SELECT * FROM " + owner + "." + name + "_RECUPERADA MINUS SELECT * FROM " + table + ";\nCOMMIT;"))));
            }
        }
        if (byTs.isEmpty() && rootSystem.isEmpty()) for (var e : f.rows("CNTERR")) {
            if (handledPdbs.contains(e[1])) continue;
            out.add(new Finding("ALTO", "No se puede leer la tabla " + e[2] + " (" + e[1] + ")", e[3], List.of(
                step(SQL, "Ver si un datafile necesita recuperacion.", "SELECT file#, error FROM v$recover_file;"),
                step(RMAN, "Comprobar bloques danados y repararlos solo si los hay.", "VALIDATE DATABASE;\nRECOVER CORRUPTION LIST;"),
                step(APP, "Si v$recover_file muestra un archivo, volver a pulsar Diagnosticar fallos."))));
            break;
        }

        if (resetlogs)
            out.add(new Finding("INFO", "Despues del OPEN RESETLOGS: respaldar de inmediato", "Empieza una encarnacion nueva de la base; los respaldos anteriores no sirven para avanzar desde ahora.", List.of(
                step(APP, "Ejecutar la estrategia de base completa (nivel 0) y comprobarla con Verificar."))));
        if (out.isEmpty())
            out.add(new Finding("OK", "No se detectan fallos", "Contenedor, instancia, " + f.files().size() + " archivos de la base, PDB, tablespaces, redo, archivado, usuarios y papelera revisados"
                + (snap == null ? "." : "; filas iguales o mayores que en la foto de " + since + "."), List.of()));
        return out;
    }

    private static long snapCount(Facts snap, String pdb, String table) {
        return snap.rows("CNT").stream().filter(r -> r[1].equals(pdb) && r[2].equals(table)).mapToLong(r -> Long.parseLong(r[3])).findFirst().orElse(0);
    }

    private static String evidence(List<String[]> files, Facts f, Map<String, String> recover) {
        var lines = new ArrayList<String>();
        for (var d : files) {
            String why = f.missing.contains(d[5]) ? "borrado del disco" : recover.containsKey(d[1]) ? "necesita recuperacion: " + recover.get(d[1]) : "estado " + d[4];
            lines.add("Datafile " + d[1] + " " + d[5] + " (" + why + ")");
        }
        return String.join("\n", lines);
    }
}
