/** Host-owned presentation. Never stored in document JSON or shared across instances. */
export type EditorAppearance={
 fontFamily?:string;fontSize?:number;lineHeight?:number;paragraphSpacing?:number;contentPadding?:number;radius?:number;
 colors?:Partial<Record<'background'|'text'|'muted'|'border'|'accent'|'raised',string>>;
};
const numeric={fontSize:[12,32,'--se-font-size','px'],lineHeight:[1.2,2.4,'--se-line-height',''],paragraphSpacing:[0,48,'--se-paragraph-spacing','px'],contentPadding:[8,64,'--se-content-padding','px'],radius:[0,24,'--se-radius','px']} as const;
const colors={background:'--se-bg',text:'--se-text',muted:'--se-muted',border:'--se-line',accent:'--se-accent',raised:'--se-raised'} as const;

export function applyAppearance(element:HTMLElement,appearance:EditorAppearance={}){
 const values:Record<string,string>={};
 for(const [key,[min,max,property,unit]] of Object.entries(numeric)){
  const value=appearance[key as keyof typeof numeric];if(value===undefined)continue;
  if(typeof value!=='number'||!Number.isFinite(value)||value<min||value>max)throw new Error(`${key} 값은 ${min}~${max} 범위여야 합니다.`);
  values[property]=`${value}${unit}`;
 }
 if(appearance.fontFamily!==undefined){
  if(typeof appearance.fontFamily!=='string'||!appearance.fontFamily.trim()||appearance.fontFamily.length>200||/[;{}\r\n]/.test(appearance.fontFamily))throw new Error('사용할 글꼴 이름과 대체 글꼴을 확인해 주세요.');
  values['--se-font-family']=appearance.fontFamily;
 }
 for(const [name,property] of Object.entries(colors)){
  const value=appearance.colors?.[name as keyof typeof colors];if(value===undefined)continue;
  if(typeof value!=='string'||!/^#[\da-f]{6}$/i.test(value))throw new Error('에디터 색상은 #RRGGBB 형식으로 지정해 주세요.');values[property]=value;
 }
 // Validate every value before changing the visible instance; omitted values restore host defaults.
 for(const property of ['--se-font-family',...Object.values(numeric).map(row=>row[2]),...Object.values(colors)]){
  if(values[property]!==undefined)element.style.setProperty(property,values[property]);else element.style.removeProperty(property);
 }
}
