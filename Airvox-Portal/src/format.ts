const number = new Intl.NumberFormat("en-IN");
const rupees = new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 });

export const formatNumber = (n: number) => number.format(n);
export const formatRupees = (n: number) => rupees.format(n);

/** "Now", "18 min ago", "3 h ago", "Yesterday", "4 days ago", then the date. */
export function timeAgo(iso: string, now = Date.now()): string {
  const mins = Math.floor((now - new Date(iso).getTime()) / 60000);
  if (mins < 1) return "Now";
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  if (days === 1) return "Yesterday";
  if (days < 7) return `${days} days ago`;
  return formatDate(iso);
}

export function formatDate(iso: string): string {
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function formatDay(iso: string): string {
  return new Date(iso).toLocaleDateString("en-IN", { day: "numeric", month: "short" });
}

export function monthName(date = new Date()): string {
  return date.toLocaleDateString("en-IN", { month: "short" });
}
