import { useRef } from 'react';

interface ChatEnterEvent {
  key: string;
  shiftKey?: boolean;
  repeat?: boolean;
  isComposing?: boolean;
  keyCode?: number;
  nativeEvent?: { isComposing?: boolean; keyCode?: number };
  currentTarget?: { tagName: string };
  preventDefault: () => void;
}

export function shouldSubmitChatOnEnter(event: ChatEnterEvent, composing = false): boolean {
  if (event.key !== 'Enter') return false;
  // Only a textarea can insert a newline; single-line forms must not submit.
  if (event.shiftKey && event.currentTarget?.tagName !== 'INPUT') return false;
  // Cancel implicit form submission even when Enter only confirms IME text.
  event.preventDefault();
  return !(event.shiftKey || composing || event.isComposing || event.nativeEvent?.isComposing
    || event.keyCode === 229 || event.nativeEvent?.keyCode === 229 || event.repeat);
}

export function useChatInputComposition() {
  const composing = useRef(false);
  return {
    onCompositionStart: () => { composing.current = true; },
    onCompositionEnd: () => { composing.current = false; },
    shouldSubmitOnEnter: (event: ChatEnterEvent) => shouldSubmitChatOnEnter(event, composing.current),
  };
}
