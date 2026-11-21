export {};
declare global {
  interface GDatum { label: string; value: number; color?: string }
  interface GApi {
    bar(data: GDatum[], o?: { target?: number; unit?: string; height?: number; width?: number; color?: string; selected?: number | null; values?: boolean; label?: string }): string;
    line(points: GDatum[] | number[], o?: { height?: number; width?: number; unit?: string; annotate?: 'minmax'; avg?: boolean; color?: string; every?: number; label?: string }): string;
    donut(items: GDatum[], o?: { size?: number; thickness?: number; center?: string; sub?: string; selected?: number | null; label?: string }): string;
    spark(values: number[], o?: { width?: number; height?: number; color?: string }): string;
    color(i: number): string;
    esc(s: unknown): string;
    fmt: { money(n: number, cur?: string): string; num(n: number, d?: number): string; pct(n: number, d?: number): string; dur(sec: number): string };
  }
  const G: GApi;
  const root: HTMLElement;
}
