whenever sqlerror exit failure rollback
set echo on
alter pluggable database FREEPDB1 open;
alter pluggable database FREEPDB1 save state;
alter session set container=FREEPDB1;
create tablespace LAB_DATOS datafile size 32M autoextend on next 8M maxsize 128M;
create user LAB_DEMO no authentication default tablespace LAB_DATOS quota 100M on LAB_DATOS;
create table LAB_DEMO.PEDIDOS (
    id number primary key,
    descripcion varchar2(100) not null,
    importe number(10,2) not null,
    registrado timestamp default systimestamp
) tablespace LAB_DATOS;
insert into LAB_DEMO.PEDIDOS(id,descripcion,importe) values(1,'Pedido de laboratorio A',125.50);
insert into LAB_DEMO.PEDIDOS(id,descripcion,importe) values(2,'Pedido de laboratorio B',240);
insert into LAB_DEMO.PEDIDOS(id,descripcion,importe) values(3,'Pedido de laboratorio C',85.25);
commit;
select * from LAB_DEMO.PEDIDOS order by id;
exit
