# Interfaz Android

La navegación principal tiene cuatro destinos: Inicio, Asignaturas, Biblioteca y Ajustes. Novedades se abre desde Inicio y vuelve con el botón de la cabecera o Atrás de Android. El acceso oficial a EV ocupa una pantalla propia.

Las fechas visibles de consultas y descargas se convierten a la zona horaria del móvil, con segundos y ajuste de horario de verano. Los registros internos conservan sus instantes UTC.

La versión visible de la aplicación se toma de `versionName` de Gradle y aparece junto a USSync en Inicio. Cada APK publicado debe incrementar el último número de la versión y el `versionCode`.

## Implementado

- Tema claro, oscuro y según el sistema, persistente; barra inferior con iconos coherentes y etiquetas.
- Inicio con configuración inicial de sesión/carpeta, pendientes, actividad y búsqueda de novedades.
- Asignaturas con búsqueda, selección persistente y edición de carpeta mediante diálogo.
- Políticas por asignatura: preguntar, descargar automáticamente o ignorar. Las reglas de carpeta tienen prioridad; las más profundas prevalecen. Se aplican a pendientes durante la siguiente consulta, sin tocar los pospuestos ni borrar descargas.
- Novedades con búsqueda por archivo, asignatura o ruta, selección múltiple y por asignatura, descargar/posponer/ignorar y deshacer las dos últimas acciones.
- Creación de reglas desde un documento, con explicación del alcance antes de confirmar. Gestión de reglas en Ajustes.
- Biblioteca conectada al árbol SAF real: carpetas, navegación atrás, búsqueda en la carpeta actual, actualizar, apertura mediante el visor del sistema y detalles. Incluye archivos añadidos por Syncthing; la lectura se renueva al navegar, actualizar o registrar una descarga. Las versiones se exploran en su carpeta real.
- Solo Wi-Fi activado inicialmente y configurable. Se comprueba al comenzar la transferencia; si no se cumple, el documento conserva su estado y muestra cómo reintentar. No implica reanudación automática ni detención ante un cambio de red a mitad de descarga.
- Hasta tres transferencias de red simultáneas y publicación serializada, errores recuperables y progreso.
- Consultas programadas cada hora, cada seis horas o diariamente mediante WorkManager, con condiciones de red y batería. Desactivadas inicialmente. Las reglas se aplican también en segundo plano; Android decide el momento efectivo de ejecución.
- En builds debug, opción «Cada minuto · Depuración»: trabajos únicos encadenados con un minuto de espera después de completar cada intento. Un token de programación invalida los trabajos anteriores al cambiar de intervalo; se conservan las condiciones de red y batería. No es un temporizador exacto. Ajustes muestra el último intento automático con segundos, incluso cuando la sesión falla.
- Sesión caducada (401 al verificar, consultar o descargar): notificación en un canal propio con acción Conectar que abre el login. Se retira al validar una sesión nueva. Es independiente de los avisos de novedades y requiere permiso de notificaciones del sistema. Los fallos de red/servidor no se presentan como sesión caducada.
- Notificaciones opcionales con permiso en Android 13+, acciones Revisar y Descargar con Wi-Fi. La segunda encola un trabajo único con red no medida, además de respetar la preferencia Wi-Fi antes de descargar.
- SEVIUS dentro de cada asignatura: catálogo público configurable, elección explícita de año/grupo, selección persistente, descarga del proyecto y su programa asociado, actualización manual y en consultas programadas. Los PDF se verifican y se publican en Información docente.
- Reglas de carpeta con filtro PDF, tamaño máximo opcional y contador de pendientes coincidentes antes de confirmar. Las reglas más específicas tienen prioridad y se pueden eliminar desde Ajustes.
- La sustitución de un archivo verifica la copia de seguridad antes de borrar el original.

## Límites y validación pendiente

- Las consultas automáticas necesitan una sesión EV vigente. Los fallos quedan reflejados en Ajustes; no se garantiza una frecuencia exacta.
- La búsqueda local se limita a la carpeta abierta. El contador de reglas usa los pendientes conocidos; no predice documentos futuros ni resuelve el solapamiento con otras reglas.
- Las versiones se pueden abrir; no hay restauración automática ni comparación visual de anotaciones.
- El parser de SEVIUS se prueba con fixtures sintéticos que verifican la asociación proyecto/programa. La compatibilidad con el servicio real y el acceso EV requieren una prueba funcional con la cuenta del usuario.
- Validación visual en dispositivo: tamaño de texto ampliado, teclado, orientación, temas y listas largas. Compilar no sustituye esta validación.

No se incorporan cuentas ni servicios en la nube. Syncthing sigue gestionándose externamente y la interfaz no afirma conocer la disponibilidad en otros dispositivos.
