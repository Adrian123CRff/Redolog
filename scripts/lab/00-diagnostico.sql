whenever sqlerror exit failure
set pagesize 100 linesize 180
select banner_full from v$version;
select name, dbid, open_mode, log_mode from v$database;
select con_id, name, open_mode from v$pdbs;
select con_id, name from v$tablespace order by con_id, name;
exit
