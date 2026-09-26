// Publish only allowlisted integration material; never copy operational docs or runtime data.
import {readFile,writeFile,mkdir,copyFile,readdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';
import {resolve,dirname} from 'node:path';
import {createHash} from 'node:crypto';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const out=resolve(process.argv[2]??resolve(root,'output/integrations'));
await mkdir(out,{recursive:true});
for(const [source,name] of [['docs/SERVICE_INTEGRATION.md','SERVICE_INTEGRATION.md'],['docs/integration/AUTH.md','auth.md'],['packages/editor/INTEGRATION.md','editor.md']]){
 await copyFile(resolve(root,source),resolve(out,name));
}
for(const service of ['project','file']){
 const source=JSON.parse(await readFile(resolve(root,`services/${service}-service/src/main/resources/openapi.json`),'utf8'));
 const paths=Object.fromEntries(Object.entries(source.paths).filter(([path])=>service==='project'?['/api/v1/integration/context','/api/v1/dev/login'].includes(path):path.startsWith('/api/v1/files')&&!path.includes('/admin/')&&!path.startsWith('/api/v1/files/downloads/')));
 const components={};
 function include(group,name){
  if(components[group]?.[name])return;
  const value=source.components?.[group]?.[name];
  if(!value)throw Error(`Missing component: ${group}/${name}`);
  (components[group]??={})[name]=value;visit(value);
 }
 function visit(value){
  if(!value||typeof value!=='object')return;
  if(value.$ref){const match=/^#\/components\/([^/]+)\/([^/]+)$/.exec(value.$ref);if(!match)throw Error(`Unsupported ref: ${value.$ref}`);include(match[1],match[2]);}
  for(const security of value.security??[])for(const name of Object.keys(security))include('securitySchemes',name);
  for(const child of Object.values(value))visit(child);
 }
 visit(paths);
 const spec={openapi:source.openapi,info:{...source.info,title:`SHNEA Platform ${service} integration API`,description:'외부 프로젝트용 공개 계약. 관리자·내부 API 제외. DEV Mock은 개발 모드에서만 제공.'},servers:[{url:'https://platform.shnea.kr'}],paths,components};
 if(JSON.stringify(spec).includes('platform-admin'))throw Error('Administrator contract leaked');
 await writeFile(resolve(out,`${service}.openapi.json`),JSON.stringify(spec,null,2)+'\n');
}
const checksums={};
for(const file of await readdir(out))if(file.endsWith('.tgz'))checksums[file]=createHash('sha256').update(await readFile(resolve(out,file))).digest('hex');
await writeFile(resolve(out,'checksums.json'),JSON.stringify(checksums,null,2)+'\n');
