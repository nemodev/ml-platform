import { resolvedRuntimeConfig } from './runtime-config';

export const environment = {
  production: true,
  ...resolvedRuntimeConfig
};
