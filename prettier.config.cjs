module.exports = {
  plugins: ["prettier-plugin-java"],
  printWidth: 120,
  tabWidth: 4,
  useTabs: false,
  endOfLine: "lf",
  overrides: [
    {
      files: "*.java",
      options: {
        parser: "java"
      }
    }
  ]
};
