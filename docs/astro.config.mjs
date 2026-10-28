// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

// Where the site is served from. The Pages workflow sets both from the repository's Pages settings,
// so nothing changes here when the repository moves to another owner or gets a custom domain.
const site = process.env.SITE_URL ?? 'https://anorys.github.io';
const base = process.env.BASE_PATH ?? '/pmm';
// The repository the "Edit page" and GitHub links point to (owner/name).
const repository = process.env.REPOSITORY ?? 'anorys/pmm';

export default defineConfig({
	site,
	base,
	trailingSlash: 'always',
	integrations: [
		starlight({
			title: 'pmm',
			description:
				'pmm checks the health of a workspace made of several Maven repositories: dependencies, builds, blockers, branches and safe rebasing.',
			logo: {
				light: './src/assets/logo-light.svg',
				dark: './src/assets/logo-dark.svg',
				alt: 'Anorys faceted hexagon',
			},
			favicon: '/favicon.svg',
			social: [{ icon: 'github', label: 'GitHub', href: `https://github.com/${repository}` }],
			editLink: { baseUrl: `https://github.com/${repository}/edit/main/docs/` },
			lastUpdated: true,
			customCss: [
				'@fontsource/signika/500.css',
				'@fontsource/signika/600.css',
				'@fontsource/heebo/400.css',
				'@fontsource/heebo/500.css',
				'@fontsource/heebo/600.css',
				'@fontsource/jetbrains-mono/400.css',
				'./src/styles/anorys.css',
			],
			sidebar: [
				{
					label: 'Getting started',
					items: [
						{ slug: 'getting-started/introduction' },
						{ slug: 'getting-started/installation' },
						{ slug: 'getting-started/quick-start' },
					],
				},
				{
					label: 'Guides',
					items: [
						{ slug: 'guides/dashboard' },
						{ slug: 'guides/dependencies' },
						{ slug: 'guides/builds' },
						{ slug: 'guides/rebasing' },
						{ slug: 'guides/ci' },
					],
				},
				{
					label: 'Reference',
					items: [
						{ slug: 'reference/commands' },
						{ slug: 'reference/configuration' },
						{ slug: 'reference/checks' },
						{ slug: 'reference/environment' },
					],
				},
				{
					label: 'Development',
					items: [
						{ slug: 'development/building' },
						{ slug: 'development/architecture' },
						{ slug: 'development/releasing' },
						{ slug: 'development/tui-style-guide' },
						{ slug: 'development/roadmap' },
					],
				},
			],
		}),
	],
});
