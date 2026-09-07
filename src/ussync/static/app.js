const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="ussync-token"]').content;
let state = null, selected = new Set(), folderRules = [], subjects = [], projects = [], libraryPath = '';
let preferences = {}, page = 'home', previousRunning = false, fetching = false;
const titles = {home:'Tu próximo curso, organizado.',explore:'Materiales, a tu medida.',sevius:'El proyecto docente correcto.',library:'Tu biblioteca sin conexión.',settings:'Hazlo tuyo.'};
function node(tag, text, attrs = {}) {
  const e = document.createElement(tag);
  if (text != null) e.textContent = text;
  for (const [k,v] of Object.entries(attrs)) {
    if (k.startsWith('on')) e.addEventListener(k.slice(2),v);
    else if (k === 'class') e.className=v;
    else if (k === 'checked') e.checked=v;
    else e.setAttribute(k,v);
  }
  return e;
}
function notice(text,error=false) { $('notice').textContent=text; $('notice').hidden=false; $('notice').classList.toggle('error',error); }
async function api(path,body) {
  const response=await fetch('/api/'+path,{method:body===undefined?'GET':'POST',headers:{'X-USSync-Token':token,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body)});
  const data=await response.json();
  if(!response.ok) throw Error(typeof data.detail==='string'?data.detail:JSON.stringify(data.detail));
  return data;
}
function action(fn) { return async event => { try { await fn(event); } catch(error) {notice(error.message,true);} }; }
const normalize = text => text.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();
function size(n) { if(n==null)return '—'; if(n<1024)return n+' B'; if(n<1048576)return (n/1024).toFixed(1)+' KiB'; return (n/1048576).toFixed(1)+' MiB'; }
function route(target) {
  if(!titles[target])return;
  page=target;
  document.querySelectorAll('.page').forEach(el=>el.hidden=el.id!==target);
  document.querySelectorAll('nav button').forEach(el=>el.classList.toggle('active',el.dataset.page===target));
  $('page-title').textContent=titles[target];
  if(target==='library')loadLibrary().catch(e=>notice(e.message,true));
}
document.querySelectorAll('[data-page]').forEach(el=>el.addEventListener('click',()=>route(el.dataset.page)));
document.querySelector('.brand').addEventListener('click',event=>{event.preventDefault();route('home');});

function applyPreferences() {
  let theme=preferences.theme||'light';
  if(theme==='system')theme=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
  document.documentElement.dataset.theme=theme;
  document.documentElement.dataset.accent=preferences.accent||'blue';
  document.documentElement.dataset.density=preferences.density||'comfortable';
  for(const key of ['theme','accent','density'])$(key).value=preferences[key]||({theme:'light',accent:'blue',density:'comfortable'}[key]);
  $('remote-sort').value=preferences.sort||'name'; $('remote-view').value=preferences.view||'tree';
}
async function savePreferences() { await api('preferences',preferences); }
for(const key of ['theme','accent','density'])$(key).addEventListener('change',action(async()=>{preferences[key]=$(key).value;applyPreferences();await savePreferences();}));
matchMedia('(prefers-color-scheme: dark)').addEventListener('change',applyPreferences);

function renderCourses() {
  const container=$('courses');container.replaceChildren();container.classList.remove('empty');
  if(!state.courses.length){container.textContent='Inicia sesión para ver tus cursos de Enseñanza Virtual.';return;}
  const table=node('table'),head=node('tr');
  ['Incluir','Curso','Subcarpeta de destino'].forEach(t=>head.append(node('th',t)));table.append(head);
  for(const course of state.courses){
    const saved=state.selected_courses.find(c=>c.id===course.id),row=node('tr');
    const check=node('input',null,{type:'checkbox','aria-label':'Incluir '+course.name,'data-course':course.id,checked:!!saved});
    const cell=node('td');cell.append(check);row.append(cell);
    const label=node('td',course.name);label.append(node('small',course.courseId||course.id));row.append(label);
    const folder=node('input',null,{'aria-label':'Carpeta de '+course.name,'data-folder':course.id,placeholder:course.name+' ['+course.id+']'});
    folder.value=saved?.folder||'';const inputCell=node('td');inputCell.append(folder);row.append(inputCell);table.append(row);
  }
  container.append(table);
}
async function saveCourses(){
  const courses=[...document.querySelectorAll('[data-course]:checked')].map(input=>({id:input.dataset.course,folder:[...document.querySelectorAll('[data-folder]')].find(e=>e.dataset.folder===input.dataset.course).value}));
  await api('courses',{courses});notice('Cursos guardados. Ya puedes escanearlos.');
}
$('save-courses').addEventListener('click',action(saveCourses));
$('login').addEventListener('click',action(async()=>{await api('login',{});notice(state.demo?'Preparando cursos ficticios…':'Completa el login en la ventana de Chromium.');await refresh(false);}));
$('scan').addEventListener('click',action(async()=>{if(state.courses.length)await saveCourses();await api('scan',{});route('explore');await refresh(false);}));

function matchingDocs(){
  const query=normalize($('remote-search').value),words=query.split(/\s+/).filter(Boolean);
  const docs=state.documents.filter(d=>words.every(w=>normalize(d.course+'/'+d.parts.join('/')).includes(w)));
  const sort=$('remote-sort').value;
  return docs.sort((a,b)=>sort==='name'?(a.course+'/'+a.parts.join('/')).localeCompare(b.course+'/'+b.parts.join('/')):a.size==null?b.size==null?0:1:b.size==null?-1:(sort==='size-desc'?b.size-a.size:a.size-b.size));
}
function countSelected(){
  const docs=state.documents.filter(d=>selected.has(d.key)),known=docs.filter(d=>d.size!=null);
  $('selection-count').textContent=`${docs.length} seleccionados · ${size(known.reduce((n,d)=>n+d.size,0))}${known.length!==docs.length?' + tamaños desconocidos':''}`;
}
function fileCheck(doc){return node('input',null,{type:'checkbox',checked:selected.has(doc.key),'aria-label':'Descargar '+doc.parts.at(-1),onchange:event=>{
  if(event.target.checked){selected.add(doc.key);folderRules=folderRules.filter(r=>!(r.course===doc.course&&r.parts.every((p,i)=>doc.parts[i]===p)));}
  else selected.delete(doc.key);countSelected();
}});}
function renderRemote(){
  if(!state)return;
  const box=$('remote-files');box.replaceChildren();box.classList.remove('empty');const docs=matchingDocs();
  countSelected();
  if(!docs.length){box.textContent=state.documents.length?'No hay coincidencias.':'Escanea tus cursos o añade proyectos de SEVIUS para ver los archivos.';return;}
  function tags(doc,parent){if(doc.downloaded)parent.append(node('span','Local',{class:'tag'}));if(doc.teaching_candidate)parent.append(node('span','Posible proyecto docente',{class:'tag'}));}
  if($('remote-view').value==='list'){
    const table=node('table'),head=node('tr');['','Archivo / ubicación','Tamaño'].forEach(t=>head.append(node('th',t)));table.append(head);
    for(const doc of docs){const row=node('tr'),check=node('td');check.append(fileCheck(doc));row.append(check);
      const name=node('td',doc.parts.at(-1),{class:'name'});tags(doc,name);name.append(node('small',doc.course+' / '+doc.parts.slice(0,-1).join('/')));row.append(name,node('td',size(doc.size),{class:'size'}));table.append(row);}
    box.append(table);
  }else{
    function branch(parent,items,depth,course){
      const groups=new Map();
      for(const doc of items){const name=depth===-1?doc.course:doc.parts[depth];if(!groups.has(name))groups.set(name,[]);groups.get(name).push(doc);}
      for(const [name,files] of groups){
        if(depth>=0&&files.every(d=>d.parts.length<=depth+1)){for(const doc of files){const row=node('div',null,{class:'file-row'}),label=node('span',name,{class:'file-name'});tags(doc,label);row.append(fileCheck(doc),label,node('span',size(doc.size),{class:'size'}));parent.append(row);}continue;}
        const details=node('details'),summary=node('summary'),label=node('label',null,{class:'check'});
        const all=files.every(d=>selected.has(d.key)),some=files.some(d=>selected.has(d.key));
        const checkbox=node('input',null,{type:'checkbox',checked:all,'aria-label':'Incluir carpeta '+name});checkbox.indeterminate=!all&&some;
        checkbox.addEventListener('click',event=>event.stopPropagation());
        checkbox.addEventListener('change',()=>{for(const d of files)checkbox.checked?selected.add(d.key):selected.delete(d.key);
          const rule={course:depth===-1?name:course,parts:depth===-1?[]:files[0].parts.slice(0,depth+1)};
          folderRules=folderRules.filter(r=>!(r.course===rule.course&&JSON.stringify(r.parts)===JSON.stringify(rule.parts)));
          if(!checkbox.checked)folderRules.push(rule);else folderRules=folderRules.filter(r=>!(r.course===rule.course&&(rule.parts.every((p,i)=>r.parts[i]===p)||r.parts.every((p,i)=>rule.parts[i]===p))));
          renderRemote();});
        label.append(checkbox,node('span',name+' · '+files.length));summary.append(label);details.append(summary);details.open=depth<1||!!$('remote-search').value;
        branch(details,files,depth+1,depth===-1?name:course);parent.append(details);
      }
    }branch(box,docs,-1,null);
  }
}
async function saveFiles(){await api('selection',{keys:[...selected],folders:folderRules});notice('Selección y exclusiones guardadas.');}
$('save-files').addEventListener('click',action(saveFiles));
$('select-visible').addEventListener('click',()=>{for(const d of matchingDocs())selected.add(d.key);folderRules=[];renderRemote();});
$('exclude-visible').addEventListener('click',()=>{for(const d of matchingDocs())selected.delete(d.key);renderRemote();});
$('remote-search').addEventListener('input',renderRemote);
$('remote-sort').addEventListener('change',action(async()=>{preferences.sort=$('remote-sort').value;if(preferences.sort!=='name')preferences.view='list';applyPreferences();renderRemote();await savePreferences();}));
$('remote-view').addEventListener('change',action(async()=>{preferences.view=$('remote-view').value;if(preferences.view==='tree')preferences.sort='name';applyPreferences();renderRemote();await savePreferences();}));
$('download').addEventListener('click',action(async()=>{await saveFiles();await api('download',{keys:[...selected]});await refresh(false);}));

function renderAssociations(){
  const select=$('association'),previous=select.value;select.replaceChildren(node('option','Carpeta propia de la asignatura',{value:''}));
  for(const c of state.selected_courses)select.append(node('option',c.name,{value:c.id}));select.value=previous;
  $('saved-projects').replaceChildren();for(const item of state.sevius){const entry=node('div',null,{class:'saved-selection'});entry.append(node('strong',item.subject.name),node('p',item.documents.map(d=>d.label).join(' · ')));$('saved-projects').append(entry);}
  if(!state.sevius.length)$('saved-projects').textContent='Todavía no has elegido proyectos de SEVIUS.';
}
function filterSubjects(){
  const query=normalize($('subject-search').value),select=$('subjects'),previous=select.value;
  select.replaceChildren(node('option','Elige una asignatura',{value:''}));
  for(const s of subjects.filter(s=>normalize(s.name+' '+s.code).includes(query)))select.append(node('option',s.name+' · '+s.code,{value:s.code}));
  if([...select.options].some(o=>o.value===previous))select.value=previous;
}
$('subject-search').addEventListener('input',filterSubjects);
$('load-subjects').addEventListener('click',action(async()=>{notice('Consultando los dos centros…');subjects=await api('sevius/subjects');filterSubjects();notice(`${subjects.length} asignaturas disponibles.`);}));
$('load-projects').addEventListener('click',action(async()=>{
  const code=$('subjects').value;if(!code)throw Error('Elige primero una asignatura.');notice('Consultando programas y grupos…');
  projects=await api('sevius/documents/'+encodeURIComponent(code));
  const years=[...new Set(projects.filter(d=>d.year).map(d=>d.year))].sort().reverse();
  $('project-year').replaceChildren(...years.map(y=>node('option',y,{value:y})),node('option','Programas por versión',{value:'programs'}));
  const saved=state.sevius.find(s=>s.subject.code===code);if(saved?.documents[0]?.year)$('project-year').value=saved.documents[0].year;
  renderProjects();await detectExisting();notice('Elige el año y marca los grupos que necesites.');
}));
function renderProjects(){
  const year=$('project-year').value,docs=projects.filter(d=>year==='programs'?d.kind==='programa':d.year===year);
  const saved=state.sevius.find(s=>s.subject.code===$('subjects').value),values=new Set((saved?.documents||[]).map(d=>d.value));
  $('project-choices').replaceChildren();for(const d of docs){const label=node('label',null,{class:'check file-row'});label.append(node('input',null,{type:'checkbox','data-project':d.value,checked:values.has(d.value)}),node('span',d.label));$('project-choices').append(label);}
  if(!docs.length)$('project-choices').textContent='No hay documentos publicados para esta selección.';
}
$('project-year').addEventListener('change',renderProjects);
async function detectExisting(){
  const container=$('existing-projects');container.replaceChildren();
  const course=state.selected_courses.find(c=>c.id===$('association').value);
  if(!course){container.append(node('p','Asocia un curso para comprobar si ya contiene un posible proyecto docente.',{class:'hint'}));return;}
  const folder=course.folder||course.name+' ['+course.id+']';
  const found=state.documents.filter(d=>d.course===folder&&d.teaching_candidate);
  const local=await api('library?'+new URLSearchParams({q:'docente'}));
  const localMatches=local.entries.filter(e=>e.path.startsWith(folder+'/')&&!e.directory);
  const names=[...new Set([...found.map(d=>d.parts.join('/')),...localMatches.map(e=>e.path)])];
  if(names.length){const warning=node('div',null,{class:'warning'});warning.append(node('strong','Posible proyecto ya incluido'),node('p','Coincidencia por nombre. Comprueba año y grupo antes de omitir SEVIUS.'));for(const name of names)warning.append(node('p',name));container.append(warning);}
  else container.append(node('p','No se ha detectado un proyecto por nombre en el escaneo ni en la biblioteca de este curso. Puedes añadirlo desde SEVIUS.',{class:'hint'}));
}
$('association').addEventListener('change',action(detectExisting));
$('save-projects').addEventListener('click',action(async()=>{if(!$('subjects').value)throw Error('Elige una asignatura.');
  await api('sevius/selection',{subject:$('subjects').value,values:[...document.querySelectorAll('[data-project]:checked')].map(e=>e.dataset.project),course_id:$('association').value||null});
  notice('Proyectos guardados. Escanea de nuevo para incorporarlos al explorador.');await refresh(true);
}));

async function loadLibrary(){
  const data=await api('library?'+new URLSearchParams({path:libraryPath,q:$('library-search').value}));
  $('library-path').textContent=state.config.dest+(libraryPath?'/'+libraryPath:'');const box=$('local-files');box.replaceChildren();
  if(!data.entries.length){box.textContent='No hay archivos aquí o no hay coincidencias.';return;}
  const table=node('table');for(const item of data.entries){const row=node('tr'),cell=node('td');
    const link=item.directory?node('button','▸ '+item.name,{class:'link',onclick:action(async()=>{libraryPath=item.path;$('library-search').value='';await loadLibrary();})}):node('a',item.name,{class:'link',href:'/api/file?'+new URLSearchParams({path:item.path,token}),target:'_blank',rel:'noopener noreferrer'});
    cell.append(link);if($('library-search').value)cell.append(node('small',item.path));row.append(cell,node('td',item.directory?'Carpeta':size(item.size),{class:'size'}));table.append(row);}
  box.append(table);if(data.truncated)box.append(node('p','Mostrando hasta 1000 resultados. Afina la búsqueda.',{class:'hint'}));
}
$('library-refresh').addEventListener('click',action(loadLibrary));
$('library-search').addEventListener('keydown',action(async e=>{if(e.key==='Enter')await loadLibrary();}));
$('library-up').addEventListener('click',action(async()=>{libraryPath=libraryPath.split('/').slice(0,-1).join('/');await loadLibrary();}));
$('config-form').addEventListener('submit',action(async e=>{e.preventDefault();
  const result=await api('settings',{dest:$('config-dest').value,concurrency:Number($('config-concurrency').value),degree:$('config-degree').value,centers:$('config-centers').value});
  notice(result.message||'Configuración guardada.');await refresh(true);
}));
$('cancel').addEventListener('click',action(async()=>{await api('cancel',{});notice('Cancelando…');}));

async function refresh(full=false){
  if(fetching)return;fetching=true;
  try{
    const incoming=await api('state'),finished=(previousRunning||(state&&state.job.serial!==incoming.job.serial))&&!incoming.job.running,initial=!state;
    state=incoming;previousRunning=state.job.running;
    $('global-status').textContent=state.job.running?'Trabajando…':state.job.error?'Revisar actividad':'Preparado';
    $('job-title').textContent=state.job.name||'Actividad';$('job-logs').textContent=state.job.logs.join('\n')||'Todavía no hay operaciones en esta sesión.';
    $('cancel').hidden=!state.job.running;
    for(const id of ['login','scan','save-courses','save-files','download','save-projects'])$(id).disabled=state.job.running;
    $('config-form').querySelector('button').disabled=state.job.running;
    if(finished&&state.job.error)notice(state.job.error,true);
    if(full||finished||initial){
      selected=new Set(state.documents.filter(d=>d.selected).map(d=>d.key));folderRules=state.excluded_folders||[];
      preferences=state.preferences||{};applyPreferences();renderCourses();renderRemote();renderAssociations();
      $('destination').textContent=state.config.dest;
      for(const key of ['dest','concurrency','degree','centers'])$('config-'+key).value=state.config[key];
      if(state.demo)$('mode').textContent='DEMO · datos ficticios';
      if(page==='library')await loadLibrary();
    }
  }finally{fetching=false;}
}
refresh(true).catch(e=>notice(e.message,true));
setInterval(()=>refresh(false).catch(e=>notice('No se puede conectar con USSync. Comprueba que el script sigue abierto. '+e.message,true)),1200);
