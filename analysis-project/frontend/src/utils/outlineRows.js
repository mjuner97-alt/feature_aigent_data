function cloneRow(row) {
  return { ...row, nodeKeys: [...(row.nodeKeys || [])] };
}

export function getOutlineSubtreeRange(rows, rowIndex) {
  if (!Array.isArray(rows) || rowIndex < 0 || rowIndex >= rows.length) return { start: rowIndex, end: rowIndex };
  const level = rows[rowIndex].level;
  let end = rowIndex + 1;
  while (end < rows.length && rows[end].level > level) end += 1;
  return { start: rowIndex, end };
}

function parentIndex(rows, rowIndex) {
  const level = rows[rowIndex]?.level ?? 1;
  for (let i = rowIndex - 1; i >= 0; i -= 1) if (rows[i].level < level) return i;
  return -1;
}

export function canMoveOutlineRow(rows, rowId, direction) {
  const index = rows.findIndex(row => row.id === rowId);
  if (index < 0 || (direction !== -1 && direction !== 1)) return false;
  const range = getOutlineSubtreeRange(rows, index);
  let targetIndex = direction === 1 ? range.end : index - 1;
  if (direction === -1) {
    while (targetIndex >= 0 && rows[targetIndex].level > rows[index].level) targetIndex -= 1;
  }
  if (targetIndex < 0 || targetIndex >= rows.length || rows[targetIndex].level !== rows[index].level) return false;
  return parentIndex(rows, index) === parentIndex(rows, targetIndex);
}

export function moveOutlineRow(rows, rowId, direction) {
  if (!canMoveOutlineRow(rows, rowId, direction)) return rows.map(cloneRow);
  const index = rows.findIndex(row => row.id === rowId);
  const range = getOutlineSubtreeRange(rows, index);
  const targetIndex = direction === -1 ? rows.slice(0, index).findLastIndex(row => row.level === rows[index].level) : range.end;
  const blockStart = direction === -1 ? targetIndex : index;
  const blockEnd = direction === -1 ? index : getOutlineSubtreeRange(rows, targetIndex).end;
  const result = rows.map(cloneRow);
  const moving = result.splice(index, range.end - index);
  const insertion = direction === -1 ? blockStart : blockEnd - (range.end - index);
  result.splice(insertion, 0, ...moving);
  return result;
}

export function moveTopLevelOutlineRow(rows, rowId, targetRowId) {
  const from = rows.findIndex(row => row.id === rowId);
  const target = rows.findIndex(row => row.id === targetRowId);
  if (from < 0 || target < 0 || rows[from].level !== 1 || rows[target].level !== 1 || from === target) return rows.map(cloneRow);
  const sourceRange = getOutlineSubtreeRange(rows, from);
  const result = rows.map(cloneRow);
  const moving = result.splice(from, sourceRange.end - from);
  const targetAfterRemoval = target > from ? target - (sourceRange.end - from) : target;
  result.splice(targetAfterRemoval, 0, ...moving);
  return result;
}

/** Insert a row without mutating the source rows or sharing node bindings. */
export function insertOutlineRow(rows, afterId, row, options = {}) {
  const result = rows.map(cloneRow);
  const inserted = cloneRow(row);
  if (!afterId) {
    result.push(inserted);
    return result;
  }
  const index = result.findIndex(item => item.id === afterId);
  if (index < 0) {
    result.push(inserted);
    return result;
  }
  let insertionIndex = index + 1;
  if (options.afterSubtree !== false) {
    const level = result[index].level;
    while (insertionIndex < result.length && result[insertionIndex].level > level) insertionIndex += 1;
  }
  result.splice(insertionIndex, 0, inserted);
  return result;
}
