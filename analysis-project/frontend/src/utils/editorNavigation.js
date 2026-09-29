export function scrollToEditorSection(container, id) {
  const escaped = typeof CSS !== 'undefined' && CSS.escape ? CSS.escape(id) : id.replace(/[^a-zA-Z0-9_-]/g, '\\$&');
  const target = container.querySelector(`#${escaped}`);
  if (!target) return false;
  target.scrollIntoView({ block: 'start', behavior: 'smooth' });
  return true;
}
