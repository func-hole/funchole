import { buttonClasses } from "@/components/Button";

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
    <div className="flex items-center justify-between pt-1 text-sm text-muted">
      <span>
        {totalElements} total &middot; page {page} of {totalPages}
      </span>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={page <= 1 || disabled}
          onClick={() => onChange(page - 1)}
          className={buttonClasses("secondary", "sm")}
        >
          Previous
        </button>
        <button
          type="button"
          disabled={page >= totalPages || disabled}
          onClick={() => onChange(page + 1)}
          className={buttonClasses("secondary", "sm")}
        >
          Next
        </button>
      </div>
    </div>
  );
}
