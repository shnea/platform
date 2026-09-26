import { useRef } from "react";

// Manual activation: arrow keys move focus; Enter/Space select a section.
export function SectionTabs<T extends string>({ id, label, items, value, disabled, onChange }: {
  id: string; label: string; items: readonly { value: T; label: string }[];
  value: T; disabled: boolean; onChange: (value: T) => void;
}) {
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  return <div className="section-tabs" role="tablist" aria-label={label}>
    {items.map((item, index) => <button key={item.value} type="button" role="tab"
      ref={node => { buttons.current[index] = node; }} id={`${id}-${item.value}`}
      aria-selected={value === item.value} aria-controls={`${id}-panel`}
      tabIndex={value === item.value ? 0 : -1} disabled={disabled}
      onClick={() => onChange(item.value)} onKeyDown={event => {
        const next = event.key === "ArrowRight" ? (index + 1) % items.length
          : event.key === "ArrowLeft" ? (index - 1 + items.length) % items.length
          : event.key === "Home" ? 0 : event.key === "End" ? items.length - 1 : -1;
        if (next >= 0) { event.preventDefault(); buttons.current[next]?.focus(); }
      }}>{item.label}</button>)}
  </div>;
}
