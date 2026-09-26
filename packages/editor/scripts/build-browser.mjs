import {build} from 'esbuild';
import {bundle} from './bundle.mjs';
import {mkdir,copyFile} from 'node:fs/promises';

await bundle({entryPoints:['src/browser.ts'],bundle:true,format:'esm',platform:'browser',target:'es2022',outfile:'dist/browser/editor.js',minify:true,legalComments:'linked'});
await build({entryPoints:['src/styles/editor.css'],bundle:true,outfile:'dist/browser/editor.css',minify:true});
await mkdir('dist/browser',{recursive:true});
await copyFile('LICENSE-LUCIDE','dist/browser/LICENSE-LUCIDE');
