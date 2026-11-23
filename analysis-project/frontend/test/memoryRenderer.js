import { createRenderer } from 'vue';

function node(type, text = '') {
  return {
    type, text, props: {}, children: [], parent: null, value: '',
    addEventListener(name, handler) { this.props[`event:${name}`] = handler; },
    removeEventListener(name) { delete this.props[`event:${name}`]; },
    setAttribute(name, value) { this.props[name] = value; },
    removeAttribute(name) { delete this.props[name]; },
    getRootNode() { return this.parent ? this.parent.getRootNode() : this; },
    tagName: type.toUpperCase(),
  };
}
export const renderer = createRenderer({
  querySelector: () => node('teleport-target'),
  createElement: type => node(type), createText: text => node('#text', text), createComment: text => node('#comment', text),
  setText: (target, text) => { target.text = text; },
  setElementText: (target, text) => { target.text = text; target.children = []; },
  patchProp: (target, name, _, value) => { target.props[name] = value; },
  parentNode: target => target.parent,
  nextSibling: target => target.parent?.children[target.parent.children.indexOf(target) + 1] || null,
  insert(target, parent, anchor) {
    if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1);
    target.parent = parent;
    const index = anchor ? parent.children.indexOf(anchor) : -1;
    parent.children.splice(index < 0 ? parent.children.length : index, 0, target);
  },
  remove(target) {
    if (target.parent) target.parent.children.splice(target.parent.children.indexOf(target), 1);
    target.parent = null;
  },
});
export const container = () => node('root');
export function find(root, predicate) {
  if (predicate(root)) return root;
  for (const child of root.children) {
    const result = find(child, predicate);
    if (result) return result;
  }
  return null;
}
export function text(root) { return root.type === '#comment' ? '' : root.text + root.children.map(text).join(''); }
