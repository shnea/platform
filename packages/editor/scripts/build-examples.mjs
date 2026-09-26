import {bundle} from './bundle.mjs';
import {mkdir,copyFile,cp} from 'node:fs/promises';

for(const kind of ['react','vue','vanilla']){
 const out=`dist/examples/${kind}`;await mkdir(out,{recursive:true});
 await bundle({entryPoints:[`examples/${kind}/main.js`],bundle:true,external:['../browser/editor.js'],format:'esm',platform:'browser',target:'es2022',outfile:`${out}/main.js`,minify:true,legalComments:'linked',define:{'process.env.NODE_ENV':'"production"','__VUE_OPTIONS_API__':'true','__VUE_PROD_DEVTOOLS__':'false','__VUE_PROD_HYDRATION_MISMATCH_DETAILS__':'false'}});
 await copyFile(`examples/${kind}/index.html`,`${out}/index.html`);
 await copyFile('examples/shared/example.css',`${out}/example.css`);
}
await cp('dist/browser','dist/examples/browser',{recursive:true});
await mkdir('dist/jsp/jsp',{recursive:true});
await cp('dist/examples/vanilla','dist/jsp/vanilla',{recursive:true});
await cp('dist/browser','dist/jsp/browser',{recursive:true});
await copyFile('examples/jsp/index.jsp','dist/jsp/jsp/index.jsp');
// JSP is executed by the host servlet container; the static catalog must not expose raw JSP.
await copyFile('examples/index.html','dist/examples/index.html');
await copyFile('examples/shared/example.css','dist/examples/example.css');
await copyFile('INTEGRATION.md','dist/examples/INTEGRATION.md');
