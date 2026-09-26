import {Icon} from './Icon';
import { useEffect, useRef, type ReactNode } from "react";

export function Dialog({
  title,
  children,
  close,
  busy,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
  busy: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const opener = document.activeElement as HTMLElement;
    ref.current?.showModal();
    return () => {
      opener?.focus();
    };
  }, []);
  useEffect(() => {
    ref.current
      ?.querySelector<HTMLElement>(
        "input:not(:disabled), textarea:not(:disabled), select:not(:disabled)",
      )
      ?.focus();
  }, [title]);
  return (
    <dialog
      ref={ref}
      aria-labelledby="dialog-title"
      onKeyDown={(e) => {
        if (e.key !== "Tab") return;
        const controls = [
          ...e.currentTarget.querySelectorAll<HTMLElement>(
            "button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled)",
          ),
        ];
        const first = controls[0],
          last = controls[controls.length - 1];
        if (e.shiftKey && document.activeElement === first) {
          e.preventDefault();
          last?.focus();
        }
        if (!e.shiftKey && document.activeElement === last) {
          e.preventDefault();
          first?.focus();
        }
      }}
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) close();
      }}
    >
      <div className="dialog-heading">
        <h2 id="dialog-title">{title}</h2>
        <button
          className="quiet"
          aria-label="닫기"
          disabled={busy}
          onClick={close}
         title="닫기" data-tooltip="닫기" data-icon-only="true"><Icon name="x"/></button>
      </div>
      {children}
    </dialog>
  );
}
