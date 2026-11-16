# Sistema de Casos Médicos

Aplicación web (Spring Boot 3 + Thymeleaf + WebSocket) para gestión hospitalaria con roles
admin, CEO, médico, enfermería y paciente. Genera historias clínicas en PDF, tiene chat en vivo
y una campanita de notificaciones que avisa al doctor o al paciente cuando llega un mensaje.

## Estructura

```
.
├── Dockerfile              # build multi-etapa (Maven -> JRE 21)
├── render.yaml             # despliegue en Render (Blueprint)
├── migraciones/            # scripts SQL para la base externa
├── pom.xml
└── src/main
    ├── java/bo/edu/casosmedicos
    │   ├── archivos/       # subida y descarga de PDFs
    │   ├── chat/           # chat en tiempo real y avisos (WebSocket)
    │   ├── pdf/            # generación de historia clínica
    │   ├── security/       # login, roles, auditoría
    │   └── web/            # controladores por rol
    └── resources
        ├── application.properties
        ├── static
        │   ├── css/        # style.css (base/login) + panel.css (paneles internos)
        │   ├── js/         # app.js (menú, campanita), tablas.js, sonido.js, chat-vivo.js
        │   ├── fonts/
        │   └── images/
        └── templates       # vistas Thymeleaf por rol
            ├── layout/     # base (head, navbar, pie), componentes y fondos por rol
            └── admin · ceo · medico · enfermeria · paciente
```

### Notificaciones de chat

- `GET /avisos/resumen` devuelve el total sin leer y las últimas conversaciones del usuario.
- `WS /ws/avisos` mantiene un canal por usuario autenticado y empuja el resumen en cuanto
  llega un mensaje, aunque el chat no esté abierto.
- La campanita está disponible para los roles `doctor` y `paciente`. Si el WebSocket no está
  disponible, el navegador consulta `/avisos/resumen` cada 20 segundos como respaldo.

### Tablas

- Todas las tablas de datos agregan automáticamente un buscador (ignora acentos y mayúsculas)
  y paginación de 10/25/50 filas con el contador «Mostrando X–Y de N».
- Para excluir una tabla (por ejemplo, un resumen de dos filas) se marca con
  `data-sin-filtro` en el elemento `<table class="adm-table">`.

### Chat en vivo

- El aviso «Escribiendo…» se difunde por el WebSocket y se oculta a los 3 segundos.
- Al recibir un mensaje suena un aviso breve generado con Web Audio (sin archivos de audio).
  Se puede silenciar con el botón «Sonido» de la campanita o de la cabecera del chat.
- El botón de clip permite adjuntar **PDF, JPG o PNG de hasta 10 MB**. El archivo se guarda en
  `mensajes_chat.adjunto_datos` (MEDIUMBLOB), no en disco, y por WebSocket solo viaja la
  ficha del archivo; la descarga pasa por `/chat/adjunto/{id}` con permiso del caso.

### Citas y casos clínicos

- El paciente solicita una cita en `/paciente/citas`: elige doctor, fecha/hora (turnos de
  15 minutos) y motivo. Solo se aceptan horarios definidos por el administrador y sin cruces
  con otras citas del doctor o del paciente.
- El doctor confirma, marca como atendida o cancela en `/medico/citas`.
- El administrador crea casos clínicos en `/admin/casos` (paciente, doctor, prioridad) y
  administra los turnos del personal en `/admin/horarios`.
- El registro de asistencia se puede exportar a CSV (compatible con Excel) desde
  `/admin/asistencia/registro`.

### Base de datos

- `migraciones/001_cv_en_base_de_datos.sql`: guarda los CV dentro de MySQL.
- `migraciones/002_relaciones_e_indices.sql`: agrega claves foráneas, claves únicas e
  índices. Ejecutar una sola vez y comprobar antes que no existan datos huérfanos.
- `migraciones/003_citas_casos_adjuntos.sql`: crea la tabla `citas`, agrega la prioridad y
  fecha de cierre a los casos, los adjuntos al chat y sus índices. Es idempotente.

Orden recomendado: **001 → 002 → 003**. Antes de ejecutar 002 haz un respaldo
(`mysqldump -u USUARIO -p BASE > respaldo.sql`).

## Variables de entorno

| Variable      | Descripción                                    |
|---------------|------------------------------------------------|
| `DB_URL`      | `jdbc:mysql://HOST:PUERTO/BASE?useSSL=true`    |
| `DB_USERNAME` | Usuario de la base de datos externa            |
| `DB_PASSWORD` | Contraseña de la base de datos externa         |
| `UPLOADS_DIR` | Carpeta temporal para PDFs de atenciones (por defecto `/var/data/uploads`) |
| `PORT`        | Lo define Render automáticamente               |

## Ejecutar en local

```bash
export DB_URL="jdbc:mysql://localhost:3306/sistema_casos_medicos"
export DB_USERNAME=root DB_PASSWORD=tu_clave UPLOADS_DIR=./uploads
mvn spring-boot:run
```

## Desplegar en Render

1. Subir este repo a GitHub (el `Dockerfile` está en la raíz).
2. En Render: **New > Blueprint** (usa `render.yaml`) o **New > Web Service > Docker**.
3. Cargar `DB_URL`, `DB_USERNAME` y `DB_PASSWORD` en *Environment*.
4. Ejecutar una sola vez los scripts de `migraciones/` en la base MySQL externa, en orden
   001 → 002 → 003.
   Los CV (PDF) se guardan dentro de la base de datos, por lo que no hace falta disco persistente
   ni plan de pago. Los CV antiguos hay que volver a subirlos desde Admin > Directorio.
