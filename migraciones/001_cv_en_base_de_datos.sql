-- Guarda los CV (PDF) dentro de MySQL en vez del disco de Render.
-- Ejecutar UNA sola vez en la base de datos externa.

ALTER TABLE doctores   ADD COLUMN cv_pdf_data MEDIUMBLOB NULL;
ALTER TABLE enfermeros ADD COLUMN cv_pdf_data MEDIUMBLOB NULL;

-- Los CV antiguos apuntaban a archivos del disco que ya no existen.
-- Se limpian para que aparezcan como "Sin CV cargado" y se puedan volver a subir
-- (Admin > Directorio).
UPDATE doctores   SET cv_pdf_ruta = NULL WHERE cv_pdf_data IS NULL;
UPDATE enfermeros SET cv_pdf_ruta = NULL WHERE cv_pdf_data IS NULL;
