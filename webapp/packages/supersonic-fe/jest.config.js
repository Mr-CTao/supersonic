/**
 * SuperSonic FE 单元测试配置。
 *
 * 复用同一 pnpm workspace 中 chat-sdk 已安装的 Jest/Babel 运行时，避免引入新依赖；阶段 4 Hook
 * 使用轻量 jsdom，不启动 Puppeteer。配置显式转换 TS/TSX，并保留路径别名、样式和静态资源处理。
 */
const path = require('path');

const sharedJestRoot = path.dirname(require.resolve('../chat-sdk/node_modules/jest/package.json'));

module.exports = {
  rootDir: __dirname,
  roots: ['<rootDir>/src'],
  // 仅把标准 *.test 文件作为测试；SemanticGraph/test.tsx 是会立即挂载 G6 的开发演示模块。
  testMatch: ['**/*.test.[jt]s?(x)'],
  testEnvironment: require.resolve('jest-environment-jsdom', {
    paths: [sharedJestRoot],
  }),
  testEnvironmentOptions: {
    url: 'http://localhost/',
  },
  transform: {
    '^.+\\.(js|jsx|mjs|cjs|ts|tsx)$': '<rootDir>/../chat-sdk/config/jest/babelTransform.js',
    '^.+\\.css$': '<rootDir>/../chat-sdk/config/jest/cssTransform.js',
    '^(?!.*\\.(js|jsx|mjs|cjs|ts|tsx|css|json)$)':
      '<rootDir>/../chat-sdk/config/jest/fileTransform.js',
  },
  moduleNameMapper: {
    '^@/(.*)$': '<rootDir>/src/$1',
    '^.+\\.(css|less|sass|scss)$': require.resolve('../chat-sdk/node_modules/identity-obj-proxy'),
  },
  moduleFileExtensions: ['ts', 'tsx', 'js', 'jsx', 'json', 'node'],
  // D3 及其几何依赖发布为 ESM；pnpm 的真实路径和软链接路径都必须进入 Babel 转换。
  transformIgnorePatterns: [
    'node_modules/.pnpm/(?!(d3-[^@/]+|internmap|delaunator|robust-predicates)@)',
    'node_modules/(?!.pnpm|d3-[^/]+|internmap|delaunator|robust-predicates)',
  ],
  clearMocks: true,
  verbose: false,
};
