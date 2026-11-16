// Build-time colour generation only; the Android app needs no Node.js dependency.
import {createRequire, registerHooks} from 'node:module';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const seed = process.argv[2] ?? '#006A6A';
const toolsDirectory = resolve(process.argv[3] ?? '.md3e-tools');
if (!/^#[0-9a-f]{6}$/i.test(seed)) throw new Error('Use an opaque #RRGGBB seed.');
const require = createRequire(import.meta.url);
// MCU 0.4.0 contains extensionless relative imports normally resolved by a bundler.
// Resolve those imports for this Node 24 build-time tool without editing the package.
registerHooks({resolve(specifier, context, nextResolve) {
  try { return nextResolve(specifier, context); }
  catch (error) {
    if (error.code === 'ERR_MODULE_NOT_FOUND' &&
        context.parentURL?.includes('/@material/material-color-utilities/') &&
        /^\.\.?\//.test(specifier) && !/\.[a-z]+$/i.test(specifier))
      return nextResolve(specifier + '.js', context);
    throw error;
  }
}});
const modulePath = require.resolve('@material/material-color-utilities', {paths: [toolsDirectory]});
const {Hct, SchemeTonalSpot, MaterialDynamicColors, argbFromHex, hexFromArgb} = await import(pathToFileURL(modulePath));
const dynamic = new MaterialDynamicColors();
const roles = [
  'primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer', 'inversePrimary',
  'secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer',
  'tertiary', 'onTertiary', 'tertiaryContainer', 'onTertiaryContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceVariant', 'onSurfaceVariant',
  'surfaceTint', 'inverseSurface', 'inverseOnSurface', 'error', 'onError', 'errorContainer',
  'onErrorContainer', 'outline', 'outlineVariant', 'scrim', 'surfaceBright', 'surfaceDim',
  'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh',
  'surfaceContainerHighest', 'primaryFixed', 'primaryFixedDim', 'onPrimaryFixed',
  'onPrimaryFixedVariant', 'secondaryFixed', 'secondaryFixedDim', 'onSecondaryFixed',
  'onSecondaryFixedVariant', 'tertiaryFixed', 'tertiaryFixedDim', 'onTertiaryFixed', 'onTertiaryFixedVariant'
];

function luminance(argb) {
  const linear = [16, 8, 0].map(shift => ((argb >>> shift) & 255) / 255)
      .map(c => c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
}
function contrast(a, b) {
  const values = [luminance(a), luminance(b)].sort((x, y) => y - x);
  return (values[0] + 0.05) / (values[1] + 0.05);
}

const output = [
  'package md3e.example', '',
  'import androidx.compose.material3.darkColorScheme',
  'import androidx.compose.material3.lightColorScheme',
  'import androidx.compose.ui.graphics.Color', '',
  `// Generated from ${seed.toUpperCase()} using Material Color Utilities 0.4.0.`,
  '// SchemeTonalSpot, standard contrast, independent light and dark roles.'
];
for (const dark of [false, true]) {
  const scheme = new SchemeTonalSpot(Hct.fromInt(argbFromHex(seed)), dark, 0.0);
  const colours = Object.fromEntries(roles.map(role => [role, dynamic[role]().getArgb(scheme)]));
  for (const [foreground, background] of [
    ['onPrimary', 'primary'], ['onPrimaryContainer', 'primaryContainer'],
    ['onSecondary', 'secondary'], ['onSecondaryContainer', 'secondaryContainer'],
    ['onTertiary', 'tertiary'], ['onTertiaryContainer', 'tertiaryContainer'],
    ['onSurface', 'surface'], ['onSurfaceVariant', 'surfaceContainer'],
    ['onError', 'error'], ['onErrorContainer', 'errorContainer']
  ]) {
    if (contrast(colours[foreground], colours[background]) < 4.5)
      throw new Error(`Insufficient normal-text contrast: ${foreground}/${background}, dark=${dark}`);
  }
  output.push('', `val Brand${dark ? 'Dark' : 'Light'} = ${dark ? 'dark' : 'light'}ColorScheme(`,
    ...roles.map(role => `    ${role} = Color(0xFF${hexFromArgb(colours[role]).slice(1).toUpperCase()}),`), ')');
}
process.stdout.write(output.join('\n') + '\n');
