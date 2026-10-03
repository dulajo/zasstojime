const number = new Intl.NumberFormat("cs-CZ");
const percent = new Intl.NumberFormat("cs-CZ", { maximumFractionDigits: 1, minimumFractionDigits: 1 });

export function formatNumber(value: number): string {
  return number.format(value);
}

export function formatPercent(value: number | null): string {
  return value == null ? "—" : `${percent.format(value)} %`;
}

export function formatDuration(seconds: number | null): string {
  if (seconds == null) return "—";
  const minutes = Math.round(Math.abs(seconds) / 60);
  const hours = Math.floor(minutes / 60);
  const remainder = minutes % 60;
  const sign = seconds < 0 ? "−" : "";
  return hours > 0 ? `${sign}${hours} h ${remainder} min` : `${sign}${minutes} min`;
}

export function formatDate(date: string): string {
  return new Intl.DateTimeFormat("cs-CZ", { day: "numeric", month: "long", year: "numeric" })
    .format(new Date(`${date}T12:00:00Z`));
}

export function formatTimestamp(value: string | null): string {
  if (!value) return "bez úspěšného sběru";
  return new Intl.DateTimeFormat("cs-CZ", {
    timeZone: "Europe/Prague", day: "numeric", month: "numeric", hour: "2-digit", minute: "2-digit",
  }).format(new Date(value));
}

export function todayInPrague(): string {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Prague", year: "numeric", month: "2-digit", day: "2-digit",
  }).formatToParts(new Date());
  const values = Object.fromEntries(parts.map(({ type, value }) => [type, value]));
  return `${values.year}-${values.month}-${values.day}`;
}
