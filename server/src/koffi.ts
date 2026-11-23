import * as koffiDefault from 'koffi';

// koffi exposes its API both on the module namespace and on `.default` (ESM interop).
const mod = koffiDefault as unknown as { default?: unknown };
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export const koffi = (mod.default ?? koffiDefault) as any;
