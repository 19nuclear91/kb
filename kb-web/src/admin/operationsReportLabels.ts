export const exact = (value: string | null) => value === null ? '—（历史未覆盖）' : value;
export const coverageLabels = { COMPLETE: '完整覆盖', PARTIAL: '部分覆盖：仅为已观察部分', NOT_STARTED: '历史未覆盖' };
