export type VideoSubtitles={mode:"sidecar"|"burned";language:string;timing:"vad_proportional";cueCount:number;srt:string;vtt:string;transcript:string};
export type SubtitleOptions={mode:"sidecar"|"burned";language:"auto"|"ko"|"en"|"ja"|"zh"|"yue";useItn:boolean};
export type VideoOptions={seconds?:number;subtitles?:SubtitleOptions|null};
export function subtitlePresentation(subtitles:VideoSubtitles|null|undefined,urls:Record<string,string>|undefined,streaming:boolean) {
 const vtt=streaming&&subtitles?.mode==="sidecar"&&subtitles.cueCount>0?urls?.["subtitles.vtt"]:undefined;
 const notice=!streaming||!subtitles?"":subtitles.cueCount===0?"자동 생성 자막 · 인식된 음성이 없어 자막이 비어 있습니다.":`자동 생성 자막 · 자막 시각은 근삿값입니다.${subtitles.mode==="burned"?" 영상에 입혀진 자막은 재생 중 끌 수 없습니다.":""}`;
 return {vtt,notice};
}
