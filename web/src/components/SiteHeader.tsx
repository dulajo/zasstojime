import { ThemeToggle } from "@/components/ThemeToggle";

export function SiteHeader() {
  return <header className="topbar"><div className="shell topbar-inner">
    <a className="brand" href="/" aria-label="Zpožděno, úvodní stránka"><span className="brand-mark">Z.</span><span>Zpožděno<span className="brand-period">.</span></span></a>
    <ThemeToggle />
  </div></header>;
}

export function SiteFooter({ back = false }: { back?: boolean }) {
  return <footer className="site-footer"><div><strong>Zpožděno.</strong><p>Neoficiální pohled na dochvilnost PID. Nejsme součástí PID ani DPP.</p></div>
    <div><p>Zdroj dat: Golemio, licence CC BY 4.0.</p>
      {back ? <a href="/">← Zpět na přehled</a> : <a href="/metodika/">Jak čísla počítáme →</a>}</div></footer>;
}
