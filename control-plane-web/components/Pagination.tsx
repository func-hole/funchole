interface PaginationProps {
  page: number;
  totalPages: number;
  totalElements: number;
  onChange: (page: number) => void;
  disabled?: boolean;
}

export function Pagination({
  page,
  totalPages,
  totalElements,
  onChange,
  disabled = false,
}: PaginationProps) {
  if (totalElements === 0) {
    return null;
  }
  return (
    <div className="flex items-center justify-between pt-4 text-sm text-zinc-500 dark:text-zinc-400">
      <span>
        {totalElements} total · page {page} of {totalPages}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={page <= 1 || disabled}
          onClick={() => onChange(page - 1)}
          className="rounded-lg border border-zinc-200 px-3 py-1.5 transition-colors hover:bg-zinc-100 disabled:opacity-40 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          Previous
        </button>
        <button
          type="button"
          disabled={page >= totalPages || disabled}
          onClick={() => onChange(page + 1)}
          className="rounded-lg border border-zinc-200 px-3 py-1.5 transition-colors hover:bg-zinc-100 disabled:opacity-40 dark:border-zinc-700 dark:hover:bg-zinc-800"
        >
          Next
        </button>
      </div>
    </div>
  );
}
