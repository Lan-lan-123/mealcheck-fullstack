import analysisIteration, { options as analysisOptions, setup as analysisSetup,
  handleSummary as analysisSummary } from './analysis-capacity.js';
import readIteration, { options as readOptions, setup as readSetup } from './read-multi.js';

export const options = {
  ...analysisOptions,
  scenarios: {
    analysis: { ...analysisOptions.scenarios.analysis, exec: 'analyze' },
    reads: { executor: 'constant-vus', vus: Number(__ENV.VUS || 50),
      duration: __ENV.DURATION || '10m', exec: 'read', gracefulStop: '15s' },
  },
  thresholds: { ...analysisOptions.thresholds, ...readOptions.thresholds },
};
export function setup() { readSetup(); return analysisSetup(); }
export function analyze(data) { analysisIteration(data); }
export function read() { readIteration(); }
export function handleSummary(data) { return analysisSummary(data); }
