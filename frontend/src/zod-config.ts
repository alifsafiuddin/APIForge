import { z } from 'zod'

// The production Content-Security-Policy forbids eval: without this, zod probes `new Function` and the
// browser reports a CSP violation (the probe's error is caught, the report is not). Imported first in main.tsx.
z.config({ jitless: true })
