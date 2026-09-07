# USSync

Tu biblioteca de Enseñanza Virtual y proyectos docentes de la Universidad de
Sevilla. Un MVP local en Python, con una interfaz en el navegador y una CLI.
Pensado inicialmente para el doble grado de Matemáticas e Ingeniería Informática.

La interfaz permite iniciar sesión, elegir cursos, escanear sus materiales,
buscar archivos, excluir lo que no necesitas y descargar una selección. SEVIUS
se consulta sin login para elegir asignaturas, años y grupos.

## Probarlo

Requiere Python 3.11 o posterior y, para iniciar sesión, un escritorio gráfico.

Linux:

```bash
./setup.sh
./run.sh
```

Se abre `http://127.0.0.1:8766` en el navegador. Mantén el proceso abierto mientras
usas la interfaz; `Ctrl+C` lo detiene. Si el puerto está ocupado:

```bash
./run.sh --port 8876
```

La instalación preparada para el primer usuario incluye un acceso directo local
`USSync.desktop`. Dependiendo del escritorio, puede ser necesario marcarlo como
«Permitir ejecutar». El script `run.sh` funciona también directamente.

Windows:

```powershell
py -m venv .venv
.venv\Scripts\python -m pip install -e ".[dev]"
.venv\Scripts\python -m playwright install chromium
.\run.cmd
```

El MVP se valida inicialmente en Linux. Windows dispone de lanzador y código
compatible, pero necesita una prueba completa en una máquina Windows.

### Demo sin cuenta universitaria

```bash
./run-demo.sh
```

Usa datos ficticios, PDF generados y carpetas `demo-library/` y `demo-state/`
separadas de la biblioteca real. Pulsa «Iniciar sesión / actualizar cursos» para
cargar dos cursos ficticios, selecciónalos, escanea y descarga. El login, escaneo
y descarga de materiales son simulados; el catálogo público de SEVIUS sigue siendo
real si lo consultas desde esa pantalla.

## Recorrido de la interfaz

### 1. Configuración

Configura desde la pantalla «Configuración»:

- Carpeta absoluta de destino; se crea al descargar.
- Concurrencia entre 1 y 8 descargas; valor inicial 4.
- Titulación y centros de SEVIUS. El doble grado usa titulación `247` y centros
  `17,3`, porque cada centro publica una parte de sus asignaturas.
- Apariencia clara, oscura o según el sistema.
- Color principal: azul, verde, violeta o naranja.
- Densidad cómoda o compacta.

La vista por carpetas/lista y la ordenación también se recuerdan. Cada curso puede
tener su propia subcarpeta, editable en «Inicio y cursos». La configuración de
descargas se escribe en `.env`; las selecciones y preferencias se guardan en el
catálogo privado. No hace falta editar archivos para el uso habitual.

Cambiar de destino no mueve ni borra lo que ya tienes. Cuando la versión remota
es conocida y la copia anterior está verificada, se reutiliza sin descargarla otra
vez. Otros documentos se consultan de nuevo.

### 2. Inicio de sesión y selección de cursos

«Iniciar sesión / actualizar cursos» abre Chromium. Completa el acceso normal de
la US, incluido MFA si se solicita. USSync no pide ni almacena tu contraseña:
conserva el estado autenticado del navegador en una carpeta privada.

Se comprueba el perfil mediante la API antes de considerar terminado el login;
las cookies de sesión se guardan explícitamente entre ejecuciones. Si la sesión
expira, vuelve a iniciar sesión. No se garantiza una duración determinada por la
universidad ni se evita una nueva petición de MFA.

Marca los cursos que quieras y, si lo deseas, cambia sus subcarpetas. Guarda la
selección o pulsa «Escanear cursos seleccionados», que la guarda antes de explorar.

### 3. Explorador remoto

El escaneo lista los adjuntos descargables y los organiza por curso y carpeta.
Todavía no transfiere el contenido de los archivos.

- Árbol de carpetas desplegable o lista plana.
- Búsqueda por nombre de archivo, asignatura y ruta, ignorando mayúsculas y tildes.
- Ordenación por nombre, mayor tamaño o menor tamaño.
- Tamaño desconocido representado como `—`: no se inventa ni se descarga un
  archivo entero para averiguarlo. La disponibilidad depende de la API.
- Casillas para incluir/excluir archivos y carpetas completas.
- Marcar o excluir los resultados visibles de una búsqueda.
- «Guardar exclusiones» recuerda la selección; una carpeta excluida afecta
  también a sus futuros archivos.
- «Descargar seleccionados» guarda las exclusiones y descarga la selección
  completa marcada, aunque algunos archivos no aparezcan por el filtro de búsqueda.
- Indicadores de archivo local y posible proyecto docente.
- Resumen de cantidad seleccionada y suma de tamaños conocidos.

Al seleccionar resultados individualmente se pueden levantar reglas de carpeta
que los bloqueaban. Revisa y guarda las exclusiones después de cambiar la selección.
Las exclusiones también se respetan desde la CLI.

El árbol representa materiales descargables que el conector reconoce; no replica
todos los elementos de la plataforma, como evaluaciones, vídeos externos o carpetas
vacías. Un escaneo parcial avisa en la actividad. Las carpetas retiradas del servidor
no provocan borrados locales.

### 4. Programas y proyectos docentes

En «Proyectos docentes»:

1. Consulta las asignaturas y busca por nombre o código.
2. Elige una asignatura y pulsa «Ver años y grupos».
3. Elige el año académico y marca los proyectos/grupos necesarios.
4. Opcionalmente asóciala a un curso seleccionado de Enseñanza Virtual para
   reunir los documentos en la misma carpeta.
5. Guarda y vuelve a escanear para incorporarlos al explorador.

Se descarga el **programa asociado a la versión del proyecto elegido**, que puede
ser diferente del programa más reciente de la asignatura. También se pueden
seleccionar programas por versión sin un proyecto.

La interfaz busca posibles proyectos ya incluidos en los nombres del escaneo y
en los archivos locales del curso asociado. Es una sugerencia, no una verificación
del contenido, año o grupo del PDF. Si ya tienes el documento correcto, deja vacía
la selección de SEVIUS para esa asignatura y guarda para evitar duplicarlo.

Los documentos se revisan de nuevo al escanear: si un proyecto cambia de versión
de programa, se toma su asociación publicada. Si desaparece un proyecto elegido,
se avisa y se conserva la copia local. No se sustituye silenciosamente por otro grupo.

### 5. Mi biblioteca

- Navegación por las carpetas descargadas y vuelta a la carpeta superior.
- Búsqueda recursiva por nombre/ruta dentro de la carpeta actual.
- Apertura de PDF en el navegador y descarga local de otros formatos.
- Consulta de tamaños y hasta 1000 resultados por búsqueda; afina el filtro para
  bibliotecas más grandes.

La búsqueda del MVP es por nombres y rutas, no por texto dentro de los PDF. La
biblioteca funciona sin conexión a la universidad, mientras el proceso local esté
abierto. Los archivos también se pueden abrir con el explorador habitual del sistema.

### 6. Actividad y cancelación

La actividad muestra login, exploración, documentos guardados, errores y resumen.
Se puede cancelar una operación; se conservan los archivos completos y se eliminan
los temporales que estuvieran en curso. Un bloqueo de estado evita ejecutar dos
trabajos de descarga/login simultáneos entre CLI y panel.

## Garantías de las descargas

- Cola asíncrona con concurrencia limitada y memoria acotada para transferencias.
- Transferencia por bloques y archivos temporales en el destino.
- Reintentos ante errores de transporte, saturación y errores transitorios del servidor.
- Espera según `Retry-After` cuando exista, limitada a 120 segundos.
- Validación de cabecera PDF y rechazo de HTML inesperado.
- Publicación mediante renombrado atómico después de verificar el archivo.
- SHA-256 para comprobar copias locales y contenido recibido.
- Evita repetir archivos con revisión remota conocida y copia local intacta.
- Revalida documentos sin revisión fiable, usando ETag/Last-Modified si la fuente
  los facilita. Un archivo sin cambios puede requerir transferencia para comparar
  su hash, especialmente en las descargas POST de SEVIUS.
- Restaura documentos borrados localmente al volver a sincronizar.
- Conserva modificaciones locales y notifica conflictos en vez de sobrescribirlas.
- Las versiones reemplazadas quedan en `.versiones/` junto al archivo. No hay todavía
  limpieza automática ni cuota de versiones; controla el espacio utilizado.
- Sufijos estables en archivos para evitar colisiones de nombres.
- No borra apuntes personales ni archivos retirados del servidor.

La UI descarga la instantánea del último escaneo. Vuelve a escanear para descubrir
novedades. Ante una caducidad durante una descarga, el MVP pide nuevo login y
reintento: la renovación coordinada dentro del lote queda pendiente.

## CLI y selector de terminal

Desde el entorno virtual:

| Comando | Función |
| --- | --- |
| `ussync ui` | Abre la interfaz local |
| `ussync ui --demo` | Interfaz con demo aislada |
| `ussync ui --no-browser --port 8876` | Servidor local sin abrir una pestaña |
| `ussync` | Menú interactivo de terminal |
| `ussync login` | Login en Chromium |
| `ussync select` | Elegir fuentes, asignaturas y grupos en la terminal |
| `ussync select --source sevius` | Selector público, sin login de Enseñanza Virtual |
| `ussync sync` | Descubrir de nuevo y descargar las selecciones guardadas |
| `ussync sync --source sevius --non-interactive` | Sincronizar SEVIUS sin preguntas |
| `ussync status` | Mostrar destino, selección y último resultado |
| `ussync demo` | Descargar PDF ficticios para comprobar el motor |

En los selectores de terminal: flechas para moverte, espacio para marcar y Enter
para confirmar. Cancelar antes de guardar conserva la selección anterior.

Opciones globales antes del comando: `--env-file`, `--dest`, `--state-dir` y
`--concurrency`. Ejemplo:

```bash
.venv/bin/ussync --dest /ruta/a/apuntes sync --source sevius
```

Las rutas relativas de configuración se resuelven desde el archivo `.env`.
Prioridad al arrancar: CLI > entorno exportado > `.env` > valores por defecto.
Una variable exportada puede prevalecer sobre un cambio de la interfaz al volver
a abrir la aplicación; elimina ese override si quieres gestionarla solo desde la UI.

## Archivos de configuración y estado

Durante desarrollo, `.env` se encuentra en la raíz del proyecto, aunque ejecutes
desde otra carpeta. Una instalación empaquetada busca `~/.config/ussync/.env`;
también puedes indicar `--env-file`. Consulta `.env.example`.

Estado privado por defecto: `~/.local/share/ussync/`.

- `ev-session.json`: cookies y almacenamiento del navegador, guardado atómicamente
  con permisos privados en sistemas POSIX. Contiene credenciales de sesión: no compartir.
- `catalog.sqlite3`: configuración de cursos, selecciones, escaneo, hashes e historial.
- `ussync.lock`: bloqueo de operaciones.

Se rechaza una carpeta de estado contenida en la biblioteca. `.env`, estado,
entorno virtual y bibliotecas de demo están excluidos de Git. El servidor solo
escucha en `127.0.0.1`, valida origen/host y exige un token local para la API.

## Estado de validación y límites

- SEVIUS: recorrido real verificado el 7 de septiembre de 2026, combinando 86
  asignaturas de los centros 17 y 3; comprobada la descarga de un PDF y su programa asociado.
- Motor y API local: pruebas automáticas con datos ficticios, sin credenciales universitarias.
- Enseñanza Virtual: conector implementado con sesión de navegador y consultas a
  APIs de cursos/contenido/adjuntos. La compatibilidad completa requiere una prueba
  con la cuenta y cursos concretos; no se presupone cobertura de todo Blackboard.
- El MVP maneja adjuntos tradicionales. Documentos Ultra embebidos, formatos de
  contenido específicos, enlaces de terceros y vídeos pueden necesitar adaptadores.
- No hay aún búsqueda dentro de PDF, programación automática, OCR, extracción de
  evaluación, apps móviles propias ni sincronización entre dispositivos integrada.
- No se ha desplegado nada en Oracle ni se ha publicado un repositorio remoto.

## Próxima etapa: dispositivos

La arquitectura acordada es USSync inicialmente en Linux y Nextcloud en una VM
Oracle ARM64 de 12 GB RAM y 50 GB de disco. Nextcloud distribuirá la biblioteca a
Linux, Windows, Android, iPhone e iPad; la sesión y el catálogo permanecerán fuera
de la carpeta compartida. En iPhone/iPad se validará la disponibilidad sin conexión,
sin prometer sincronización continua en segundo plano.

Una migración posterior de USSync al servidor requiere resolver login/MFA remoto.
Estos son planes, no funcionalidades desplegadas. Detalle en [PLAN.md](PLAN.md).

## Desarrollo y pruebas

```bash
.venv/bin/python -m pip install -e '.[dev]'
.venv/bin/pytest -q
.venv/bin/ruff check src tests
.venv/bin/ruff format --check src tests
```

Las pruebas cubren configuración, asociación de programa/proyecto, descargas
incrementales, concurrencia, exclusiones, modificaciones locales, versiones,
reubicación, rechazo de HTML, API local y recorrido de demo. El workflow de GitHub
Actions ejecuta pruebas y estilo cuando se publique el proyecto.

Estructura: conectores en `src/ussync/connectors/`, motor en `engine.py`, catálogo
en `storage.py`, CLI en `cli.py`, servidor local en `web.py` e interfaz sin proceso
de compilación en `static/`.

Proyecto independiente, sin afiliación oficial a la Universidad de Sevilla.
La licencia de publicación queda pendiente de elección del autor. No incluyas
materiales docentes reales, sesiones ni datos personales al preparar una demo pública.
