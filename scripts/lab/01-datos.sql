-- Datos de prueba del laboratorio. Se puede ejecutar varias veces sin error:
--   Get-Content scripts\lab\01-datos.sql | docker exec -i -u oracle rman-lab sqlplus -s / as sysdba
whenever sqlerror exit failure rollback
set echo on serveroutput on
declare
    v_mode varchar2(20);
begin
    select open_mode into v_mode from v$pdbs where name = 'FREEPDB1';
    if v_mode <> 'READ WRITE' then
        execute immediate 'alter pluggable database FREEPDB1 open';
    end if;
end;
/
alter pluggable database FREEPDB1 save state;
alter session set container=FREEPDB1;
declare
    n number;
begin
    -- La imagen no define db_create_file_dest (OMF), por eso la ruta del datafile es explicita.
    select count(*) into n from dba_tablespaces where tablespace_name = 'LAB_DATOS';
    if n = 0 then
        execute immediate q'[create tablespace LAB_DATOS datafile '/opt/oracle/oradata/FREE/FREEPDB1/lab_datos01.dbf' size 32M autoextend on next 8M maxsize 128M]';
    end if;
    select count(*) into n from dba_users where username = 'LAB_DEMO';
    if n = 0 then
        execute immediate 'create user LAB_DEMO no authentication default tablespace LAB_DATOS quota 100M on LAB_DATOS';
    end if;
    select count(*) into n from dba_tables where owner = 'LAB_DEMO' and table_name = 'PEDIDOS';
    if n = 0 then
        execute immediate 'create table LAB_DEMO.PEDIDOS (
            id number primary key,
            descripcion varchar2(100) not null,
            importe number(10,2) not null,
            registrado timestamp default systimestamp
        ) tablespace LAB_DATOS';
    end if;
end;
/
merge into LAB_DEMO.PEDIDOS p
using (select 1 id, 'Pedido de laboratorio A' descripcion, 125.50 importe from dual union all
       select 2, 'Pedido de laboratorio B', 240 from dual union all
       select 3, 'Pedido de laboratorio C', 85.25 from dual) s
on (p.id = s.id)
when not matched then insert (id, descripcion, importe) values (s.id, s.descripcion, s.importe);
commit;
select * from LAB_DEMO.PEDIDOS order by id;
exit
