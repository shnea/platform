export const independentDefinitions:Record<string,{kind:string;accept:string;description:string}>={
 subtitles:{kind:'video.subtitles',accept:'.wav,.mp3,.flac,.ogg,.m4a,.mp4,.mov,.webm,.mkv,.aac,.aiff',description:'독립 자막 작업입니다. SRT·VTT를 생성하며 영상에 입히거나 번역하지 않습니다. 시각은 자동 추정 근삿값입니다.'},
 pdf:{kind:'pdf.extract',accept:'.pdf',description:'페이지별 내장 텍스트 또는 스캔 페이지 OCR을 추출합니다. 페이지 이미지는 결과에 포함되지 않습니다.'},
 ocr:{kind:'ocr.recognize',accept:'.png,.jpg,.jpeg,.jfif,.gif,.webp,.bmp,.ico,.tif,.tiff,.heic,.heif,.avif',description:'이미지 첫 프레임의 문자와 정규화된 줄 위치를 인식합니다. confidence는 정답률이 아닙니다.'},
 stt:{kind:'stt.transcribe',accept:'.wav,.mp3,.flac,.ogg,.m4a,.mp4,.mov,.webm,.mkv,.aac,.aiff',description:'첫 오디오 트랙의 전사와 VAD 구간을 생성합니다. 구간 시각은 단어별 정렬이나 화자 구분이 아닙니다.'},
 tts:{kind:'tts.synthesize',accept:'',description:'단일 speech.wav(24kHz 모노 PCM16)를 생성합니다. ZIP이나 별도 합성 파일을 요청하지 않습니다.'},
 thumbnail:{kind:'video.thumbnail',accept:'.mp4,.m4v,.mov,.mkv,.webm',description:'지정 시점의 JPEG 썸네일만 생성합니다. 영상 HLS 변환이나 별도 STT는 실행하지 않습니다.'},
 voices:{kind:'tts.voice.register',accept:'.wav,.mp3,.flac,.ogg,.m4a,.aac',description:'프리셋은 즉시 등록하고 참조 음성은 3초 초과·30초 이하·64MiB 이하를 검증합니다. 3초 이하는 등록할 수 없습니다. 브라우저에서 길이를 읽지 못하거나 저장된 파일 ID를 사용하면 서버가 등록 전에 확인합니다. 실제 말한 대본과 명료한 녹음을 사용하세요. 대본 일치·목소리 품질은 자동 판정하지 않습니다.'},
};
export type IndependentOptions={language:string;itn:boolean;correction:boolean;mode:string;seconds:string;voice:string;instruction:string};
export function independentOptions(menu:string,value:IndependentOptions):Record<string,unknown> {
 if(menu==='voices')return {};
 if(menu==='pdf')return {mode:value.mode,language:value.language,language_correction:value.correction};
 if(menu==='ocr')return {language:value.language,language_correction:value.correction};
 if(menu==='thumbnail') {
  const seconds=Number(value.seconds);if(!Number.isFinite(seconds)||seconds<0||seconds>3600)throw new Error('썸네일 시점은 0~3600초입니다.');
  return {seconds};
 }
 if(menu==='tts')return {...(value.voice?{voice_id:value.voice}:{}),instruct:value.instruction};
 if(menu==='stt'||menu==='subtitles')return {language:value.language,use_itn:value.itn};
 throw new Error('지원하지 않는 독립 작업입니다.');
}
export function taskMenu(kind:unknown){return Object.entries(independentDefinitions).find(([,value])=>value.kind===kind)?.[0];}
export function subtitleCues(text:string) {
 const cues:{start:number;end:number;text:string}[]=[];
 for(const block of text.replace(/^\uFEFF/,'').replaceAll('\r\n','\n').split(/\n\s*\n/)) {
  const lines=block.split('\n'),index=lines.findIndex(line=>line.includes(' --> '));if(index<0)continue;
  const match=lines[index].match(/^(\d{2,}:\d{2}:\d{2}\.\d{3}) --> (\d{2,}:\d{2}:\d{2}\.\d{3})(?:\s.*)?$/);if(!match)continue;
  const seconds=(value:string)=>{const parts=value.split(':').map(Number);return parts[0]*3600+parts[1]*60+parts[2];};
  cues.push({start:seconds(match[1]),end:seconds(match[2]),text:lines.slice(index+1).join('\n')});if(cues.length>=100)return cues;
 }
 return cues;
}
