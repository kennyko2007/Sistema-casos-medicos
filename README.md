# Sistema de Casos Médicos

Aplicación web (Spring Boot 3 + Thymeleaf + WebSocket) para gestión hospitalaria con roles
admin, CEO, médico, enfermería y paciente. Genera historias clínicas en PDF y tiene chat en vivo.

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
    │   ├── chat/           # chat en tiempo real (WebSocket)
    │   ├── pdf/            # generación de historia clínica
    │   ├── security/       # login, roles, auditoría
    │   └── web/            # controladores por rol
    └── resources
        ├── application.properties
        ├── static/         # css, js, fuentes, imágenes
        └── templates/      # vistas Thymeleaf por rol
```

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
4. Ejecutar una sola vez `migraciones/001_cv_en_base_de_datos.sql` en la base MySQL externa.
   Los CV (PDF) se guardan dentro de la base de datos, por lo que no hace falta disco persistente
   ni plan de pago. Los CV antiguos hay que volver a subirlos desde Admin > Directorio.
