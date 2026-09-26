import type {EditorAppearance} from '@shnea/editor';
import {Icon} from '../../shared/Icon';
import './editor-workspace.css';

const fonts=[['','서비스 기본 글꼴'],['"Malgun Gothic", "Apple SD Gothic Neo", sans-serif','고딕'],['"Batang", "AppleMyungjo", serif','명조'],['ui-monospace, Consolas, monospace','고정폭']] as const;
const palettes={host:undefined,light:{background:'#ffffff',text:'#183126',muted:'#52695c',border:'#cdd7ce',accent:'#245c43',raised:'#edf2ed'},dark:{background:'#182220',text:'#e8eee8',muted:'#a5b7ae',border:'#34443e',accent:'#b0e1c7',raised:'#202d29'}} satisfies Record<string,EditorAppearance['colors']>;
export function EditorAppearanceSettings({value,onChange}:{value:EditorAppearance;onChange:(value:EditorAppearance)=>void}){
 const set=(patch:Partial<EditorAppearance>)=>onChange({...value,...patch});
 return <details className="editor-appearance"><summary>에디터 모양 설정</summary><p className="small muted">편집과 읽기에 함께 적용됩니다. 본문 JSON에는 저장되지 않으며, 연결하는 서비스에서 지정할 수 있습니다.</p>
 <div className="editor-appearance-fields">
  <label>글꼴<select aria-label="글꼴" value={value.fontFamily??''} onChange={e=>set({fontFamily:e.target.value||undefined})}>{fonts.map(([font,name])=><option value={font} key={font}>{name}</option>)}</select></label>
  <label>본문 크기<select aria-label="본문 크기" value={value.fontSize??16} onChange={e=>set({fontSize:Number(e.target.value)})}>{[14,16,18,20,24].map(size=><option key={size} value={size}>{size}px</option>)}</select></label>
  <label>줄 간격<select aria-label="줄 간격" value={value.lineHeight??1.7} onChange={e=>set({lineHeight:Number(e.target.value)})}>{[1.4,1.7,2,2.2].map(size=><option key={size} value={size}>{size}배</option>)}</select></label>
  <label>문단 간격<select aria-label="문단 간격" value={value.paragraphSpacing??''} onChange={e=>set({paragraphSpacing:e.target.value===''?undefined:Number(e.target.value)})}><option value="">글자 크기에 맞춤</option>{[8,16,24,32].map(size=><option key={size} value={size}>{size}px</option>)}</select></label>
  <label>안쪽 여백<select aria-label="안쪽 여백" value={value.contentPadding??''} onChange={e=>set({contentPadding:e.target.value===''?undefined:Number(e.target.value)})}><option value="">화면 크기에 맞춤</option>{[12,16,24,32,48].map(size=><option key={size} value={size}>{size}px</option>)}</select></label>
  <label>모서리<select aria-label="모서리" value={value.radius??8} onChange={e=>set({radius:Number(e.target.value)})}>{[0,4,8,16].map(size=><option key={size} value={size}>{size}px</option>)}</select></label>
  <label>문서 색상<select aria-label="문서 색상" value={!value.colors?'host':value.colors.background===palettes.light.background?'light':'dark'} onChange={e=>set({colors:palettes[e.target.value as keyof typeof palettes]})}><option value="host">서비스 테마</option><option value="light">밝은 문서</option><option value="dark">어두운 문서</option></select></label>
 </div><button className="secondary" onClick={()=>onChange({})}><Icon name="rotate-ccw"/>기본 모양으로</button>
 </details>;
}
