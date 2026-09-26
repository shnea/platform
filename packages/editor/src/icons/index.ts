export {iconNodes,type IconName} from './lucide.js';
import {iconNodes,type IconName} from './lucide.js';

/** Static, local Lucide nodes. The surrounding control owns its accessible name. */
export function createIcon(doc:Document,name:IconName){
 const svg=doc.createElementNS('http://www.w3.org/2000/svg','svg');
 for(const [key,value] of Object.entries({viewBox:'0 0 24 24',width:'20',height:'20',fill:'none',stroke:'currentColor','stroke-width':'2','stroke-linecap':'round','stroke-linejoin':'round','aria-hidden':'true',focusable:'false',class:'shnea-icon'}))svg.setAttribute(key,value);
 for(const [tag,attributes] of iconNodes[name]){const child=doc.createElementNS(svg.namespaceURI,tag);for(const [key,value] of Object.entries(attributes))child.setAttribute(key,value);svg.append(child);}
 return svg;
}

/** Keep text for consequential actions; compact controls retain their Korean name. */
export function decorateAction(element:HTMLElement,name:IconName,label:string,iconOnly=false){
 element.replaceChildren(createIcon(element.ownerDocument,name));
 element.classList.add('shnea-action');
 if(iconOnly){element.setAttribute('aria-label',label);element.title=label;element.dataset.tooltip=label;element.classList.add('shnea-icon-only');}
 else element.append(element.ownerDocument.createTextNode(label));
}
