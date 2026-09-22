package edu.respaldos;

import edu.respaldos.Models.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

public final class BackupService implements AutoCloseable {
    public static final String ZONE = "America/Guatemala";
    final Catalog catalog;
    final Path runtime;
    private final Rman rman;
    private final Scheduler scheduler;
    private final ExecutorService workers = Executors.newFixedThreadPool(3);
    private final Map<String,String> active = new ConcurrentHashMap<>();
    private final Map<String,Object> diagnostics = new ConcurrentHashMap<>();

    public BackupService(Catalog catalog, Path runtime, Rman rman) throws Exception {
        this.catalog=catalog; this.runtime=runtime; this.rman=rman;
        // A stopped Java process cannot establish whether an Oracle job also stopped.
        for (var e : catalog.executions()) {
            if (List.of("EJECUTANDO", "INCIERTO").contains(e.status())) {
                active.put(e.databaseId(),e.id());
                catalog.update(finish(e,"INCIERTO",null,"Proceso anterior sin cierre confirmado. Comprueba RMAN antes de liberar esta base."));
            }
        }
        var props = new Properties();
        props.setProperty("org.quartz.scheduler.instanceName","GestorRMAN");
        props.setProperty("org.quartz.threadPool.threadCount","2");
        props.setProperty("org.quartz.threadPool.makeThreadsDaemons","true");
        props.setProperty("org.quartz.jobStore.class","org.quartz.simpl.RAMJobStore");
        scheduler = new StdSchedulerFactory(props).getScheduler();
        scheduler.getContext().put("service",this);
        for (var s : catalog.strategies()) schedule(s);
        scheduler.start();
    }
    public synchronized void save(Strategy s) throws Exception {
        catalog.database(s.databaseId());
        Models.require(!active.containsKey(s.databaseId()),"Hay una ejecucion activa en esta base.");
        catalog.save(s); schedule(s);
    }
    public synchronized void delete(String id) throws Exception {
        Strategy s=catalog.strategy(id);
        Models.require(!active.containsKey(s.databaseId()),"Espera a que termine la ejecucion.");
        scheduler.deleteJob(new JobKey(id)); catalog.deleteStrategy(id);
    }
    private void schedule(Strategy s) throws Exception {
        scheduler.deleteJob(new JobKey(s.id()));
        if (!s.enabled()) return;
        var job=JobBuilder.newJob(ScheduledBackup.class).withIdentity(s.id()).usingJobData("strategyId",s.id()).storeDurably().build();
        scheduler.addJob(job,true);
        for (String time:s.times()) {
            var t=LocalTime.parse(time);
            scheduler.scheduleJob(TriggerBuilder.newTrigger().withIdentity(s.id()+"_"+time).forJob(job)
                .withSchedule(CronScheduleBuilder.dailyAtHourAndMinute(t.getHour(),t.getMinute())
                    .inTimeZone(TimeZone.getTimeZone(ZONE)).withMisfireHandlingInstructionDoNothing()).build());
        }
    }
    public static final class ScheduledBackup implements Job {
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                var service=(BackupService)context.getScheduler().getContext().get("service");
                service.run(context.getMergedJobDataMap().getString("strategyId"),"BACKUP","HORARIO",context.getScheduledFireTime().toInstant().toString());
            } catch (Exception e) { throw new JobExecutionException(e,false); }
        }
    }
    public synchronized String run(String strategyId, String operation, String source, String planned) throws Exception {
        var s=catalog.strategy(strategyId); var db=catalog.database(s.databaseId());
        String script=Rman.script(s,operation);
        String id=UUID.randomUUID().toString();
        String now=Instant.now().toString();
        String occurrence=source.equals("HORARIO") ? s.id()+"|"+planned : null;
        var execution=new Execution(id,s.id(),s.name(),db.id(),operation,source,planned,now,null,"EJECUTANDO",null,"RMAN en ejecucion.");
        if (active.containsKey(db.id())) {
            if (source.equals("MANUAL")) throw new IllegalStateException("Esta base tiene una ejecucion activa o sin cierre confirmado.");
            catalog.insert(finish(execution,"OMITIDA",null,"Otra ejecucion ocupa esta base. No se inicio RMAN."),occurrence);
            return id;
        }
        Path dir=runtime.resolve("executions").resolve(id);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("script.rman"),script);
        if (!catalog.insert(execution,occurrence)) return "duplicada";
        active.put(db.id(),id);
        workers.submit(() -> {
            boolean uncertain=false;
            try {
                var result=rman.execute(db,script,dir.resolve("output.log"));
                uncertain=result.timedOut();
                catalog.update(finish(execution,uncertain?"INCIERTO":Rman.successful(result)?"CORRECTA":"FALLIDA",result.code(),
                    uncertain?"Tiempo de espera agotado. Comprueba el proceso RMAN en Oracle; base bloqueada para nuevas ejecuciones.":
                    Rman.successful(result)?operation.equals("VALIDATE")?"RMAN verifico la lectura de los respaldos. No equivale a una recuperacion de datos probada.":"RMAN termino el respaldo sin errores.":"RMAN informo un error. Revisa el registro."));
            } catch (Exception e) {
                uncertain = e instanceof InterruptedException;
                try { catalog.update(finish(execution,uncertain?"INCIERTO":"FALLIDA",null,e.getMessage()==null?e.getClass().getSimpleName():e.getMessage())); }
                catch(Exception persistenceError) { uncertain=true; System.err.println("No se pudo guardar el resultado de "+id); }
            } finally { if(!uncertain) active.remove(db.id(),id); }
        });
        return id;
    }
    private static Execution finish(Execution e,String status,Integer code,String message) {
        return new Execution(e.id(),e.strategyId(),e.strategyName(),e.databaseId(),e.operation(),e.source(),e.plannedAt(),e.startedAt(),Instant.now().toString(),status,code,message);
    }
    public Map<String,Object> diagnose(String id) throws Exception {
        var result=rman.diagnose(catalog.database(id),runtime.resolve("diagnostics").resolve(id));
        diagnostics.put(id,result); return result;
    }
    public Map<String,Object> state() throws Exception {
        var next=new HashMap<String,String>();
        for(var s:catalog.strategies()) {
            for(var t:scheduler.getTriggersOfJob(new JobKey(s.id()))) {
                if(t.getNextFireTime()!=null) next.merge(s.id(),t.getNextFireTime().toInstant().toString(),(a,b)->a.compareTo(b)<0?a:b);
            }
        }
        return Map.of("databases",catalog.databases(),"strategies",catalog.strategies(),"executions",catalog.executions(),
            "diagnostics",diagnostics,"active",active,"nextRuns",next,"timezone",ZONE,"serverTime",Instant.now().toString());
    }
    public void close() throws Exception { scheduler.shutdown(false); workers.shutdown(); }
}
