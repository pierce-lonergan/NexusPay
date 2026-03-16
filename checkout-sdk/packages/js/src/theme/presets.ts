/**
 * Built-in theme presets.
 * Each preset provides a complete Appearance configuration.
 */

import type { Appearance } from '../types';
import { NIGHT_VARIABLES, FLAT_VARIABLES } from './tokens';

export const PRESET_DEFAULT: Appearance = {
  theme: 'default',
  variables: {},
  rules: {},
};

export const PRESET_NIGHT: Appearance = {
  theme: 'night',
  variables: NIGHT_VARIABLES,
  rules: {
    '.Input': {
      borderColor: '#334155',
      backgroundColor: '#1E293B',
      color: '#E2E8F0',
    },
    '.Input:hover': {
      borderColor: '#475569',
    },
    '.Input::placeholder': {
      color: '#64748B',
    },
    '.Input:-webkit-autofill': {
      WebkitBoxShadow: '0 0 0 1000px #1E293B inset',
      WebkitTextFillColor: '#E2E8F0',
    },
    '.Label': {
      color: '#E2E8F0',
    },
    '.Tab': {
      color: '#E2E8F0',
    },
    '.Tab:hover': {
      backgroundColor: 'rgba(59, 130, 246, 0.08)',
    },
    '.Tab--selected': {
      backgroundColor: 'rgba(59, 130, 246, 0.12)',
    },
  },
};

export const PRESET_FLAT: Appearance = {
  theme: 'flat',
  variables: FLAT_VARIABLES,
  rules: {
    '.Input': {
      boxShadow: 'none',
      borderRadius: '0',
    },
    '.Button': {
      borderRadius: '0',
    },
    '.Tab': {
      borderRadius: '0',
    },
  },
};

export const PRESETS: Record<string, Appearance> = {
  default: PRESET_DEFAULT,
  night: PRESET_NIGHT,
  flat: PRESET_FLAT,
};
