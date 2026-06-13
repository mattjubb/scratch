// Date helpers (all in local time, formatted as YYYY-MM-DD).

export function toDateStr(d) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// Most recent business day strictly before today (Mon-Fri).
export function lastBusinessDate() {
  const d = new Date();
  do {
    d.setDate(d.getDate() - 1);
  } while (d.getDay() === 0 || d.getDay() === 6);
  return toDateStr(d);
}

// Default close-mode as-of: end of the last business day (datetime-local value).
export function defaultAsOf() {
  return `${lastBusinessDate()}T23:59`;
}

// A Date as a datetime-local input value: "YYYY-MM-DDTHH:MM".
export function toLocalInput(d) {
  const p = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}

// Convert a datetime-local picker value to the API as-of timestamp. The
// wall-clock value is treated as UTC to match the (UTC) mock data.
export function asOfParam(localValue) {
  if (!localValue) return undefined;
  const withSeconds = localValue.length === 16 ? `${localValue}:00` : localValue;
  return `${withSeconds}Z`;
}

export function formatAsAt(iso) {
  if (!iso) return '';
  return iso.replace('T', ' ').replace(/(\.\d+)?Z$/, ' UTC');
}
