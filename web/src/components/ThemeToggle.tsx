"use client";

import { useEffect, useState } from "react";

type Theme = "auto" | "light" | "dark";
const next: Record<Theme, Theme> = { auto: "light", light: "dark", dark: "auto" };
const labels: Record<Theme, string> = {
  auto: "Vzhled podle systému",
  light: "Světlý vzhled",
  dark: "Tmavý vzhled",
};

function apply(theme: Theme) {
  const root = document.documentElement;
  if (theme === "auto") delete root.dataset.theme;
  else root.dataset.theme = theme;
  try {
    if (theme === "auto") localStorage.removeItem("theme");
    else localStorage.setItem("theme", theme);
  } catch {
    // Bez úložiště se volba jen nezapamatuje.
  }
}

/** Nenápadný přepínač vzhledu: podle systému → světlý → tmavý → podle systému. */
export function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("auto");
  useEffect(() => {
    const saved = document.documentElement.dataset.theme;
    if (saved === "light" || saved === "dark") setTheme(saved);
  }, []);

  const label = `${labels[theme]}. Kliknutím přepnete na: ${labels[next[theme]].toLowerCase()}.`;
  return <button type="button" className="theme-toggle" aria-label={label} title={label}
    onClick={() => { const value = next[theme]; apply(value); setTheme(value); }}>
    {theme === "light" ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden>
      <circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" /></svg>
      : theme === "dark" ? <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
        <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" /></svg>
      : <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden>
        <circle cx="12" cy="12" r="9" /><path d="M12 3a9 9 0 0 1 0 18z" fill="currentColor" stroke="none" /></svg>}
  </button>;
}
