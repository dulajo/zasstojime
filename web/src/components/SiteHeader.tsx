import type { ReactNode } from "react";

const links = [
  { href: "/", label: "Přehled", key: "overview" },
  { href: "/moje-trasa/", label: "Moje trasa", key: "my-route" },
  { href: "/metodika/", label: "Metodika", key: "method" },
] as const;

export function SiteHeader({ active, extra }: { active: (typeof links)[number]["key"]; extra?: ReactNode }) {
  return <header className="topbar"><div className="shell topbar-inner">
    <a className="brand" href="/" aria-label="Zpožděno, úvodní stránka"><span className="brand-mark">Z.</span><span>Zpožděno<span className="brand-period">.</span></span></a>
    <nav aria-label="Hlavní navigace">{links.map((link) =>
      <a key={link.key} className={link.key === active ? "active" : undefined} href={link.href}
        aria-current={link.key === active ? "page" : undefined}>{link.label}</a>)}</nav>
    {extra ?? <span className="topbar-spacer" aria-hidden />}
  </div></header>;
}
