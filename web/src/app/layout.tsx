import type { Metadata } from "next";
import { Providers } from "@/components/Providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Zpožděno — dochvilnost pražské dopravy",
  description: "Dochvilnost PID podle skutečných odjezdů ze zastávek. Každé číslo má vysvětlení.",
};

// Ruční volbu vzhledu nastaví před vykreslením, aby stránka neproblikla.
const themeScript = "try{var t=localStorage.getItem('theme');if(t==='light'||t==='dark')document.documentElement.dataset.theme=t}catch(e){}";

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="cs" suppressHydrationWarning>
    <head><script dangerouslySetInnerHTML={{ __html: themeScript }} /></head>
    <body><Providers>{children}</Providers></body>
  </html>;
}
