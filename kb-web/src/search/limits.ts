const configured = Number(import.meta.env.VITE_SEARCH_MAX_QUERY_CODEPOINTS ?? 8000);
export const searchMaxQueryCodepoints = Number.isSafeInteger(configured) && configured > 0 ? configured : 8000;
