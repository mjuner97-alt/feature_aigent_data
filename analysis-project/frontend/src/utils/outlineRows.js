function cloneRow(row) {
  return { ...row, nodeKeys: [...(row.nodeKeys || [])] };
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
