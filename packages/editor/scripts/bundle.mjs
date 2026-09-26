import {build} from 'esbuild';
import {readFile,readdir,writeFile} from 'node:fs/promises';
import {dirname,join,resolve} from 'node:path';

/** Keep the actual bundled dependencies' license texts beside each distributable. */
export async function bundle(options){
 const result=await build({...options,metafile:true}),packages=new Map();
 for(const input of Object.keys(result.metafile.inputs)){
  if(!input.includes('node_modules'))continue;
  let folder=dirname(resolve(input));
  while(folder!==dirname(folder)){
   try{const metadata=JSON.parse(await readFile(join(folder,'package.json'),'utf8'));packages.set(folder,metadata);break;}catch(error){if(error.code!=='ENOENT')throw error;}
   folder=dirname(folder);
  }
 }
 const notices=[];
 for(const [folder,metadata] of packages){
  const files=(await readdir(folder)).filter(name=>/^(licen[sc]e|copying|notice)([.-]|$)/i.test(name));
  if(!files.length)throw Error(`배포 라이선스를 찾지 못했습니다: ${metadata.name}`);
  notices.push(`${metadata.name}@${metadata.version}\n${(await Promise.all(files.map(name=>readFile(join(folder,name),'utf8')))).join('\n')}`);
 }
 await writeFile(join(dirname(options.outfile),'THIRD-PARTY-NOTICES.txt'),notices.join('\n\n---\n\n'));
}
