export interface LongTaskEditorState<TNode = unknown, TOutline = unknown> {
  nodes: TNode[];
  outline: TOutline | null;
  title: string;
}

export function cloneEditorState<T>(state: T): T {
  return JSON.parse(JSON.stringify(state)) as T;
}

export function captureEditorState<TNode, TOutline>(nodes: TNode[], outline: TOutline | null, title: string): LongTaskEditorState<TNode, TOutline> {
  return cloneEditorState({ nodes, outline, title });
}

export function restoreEditorState<TNode, TOutline>(state: LongTaskEditorState<TNode, TOutline>): LongTaskEditorState<TNode, TOutline> {
  return cloneEditorState(state);
}
