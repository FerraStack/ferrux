// SPDX-License-Identifier: Apache-2.0
//! Finds URLs in terminal text, so renderers can underline them and open
//! them on tap like OSC 8 hyperlinks.
//!
//! A small hand-written scanner rather than a regex: it runs over the visible
//! screen on every snapshot, needs no extra dependency, and handles the cases
//! that matter in terminal output (sentence punctuation after a URL, URLs in
//! parentheses, Markdown links, wrapped lines).

use std::ops::Range;

/// Prefixes that start a URL. `www.` URLs get `https://` prepended.
const PREFIXES: &[&str] = &[
    "https://", "http://", "ftp://", "sftp://", "ssh://", "git://", "file://", "mailto:", "www.",
];

/// Punctuation that is part of a URL in the middle but usually ends a
/// sentence when it comes last.
const TRAILING: &[char] = &['.', ',', ';', ':', '!', '?'];

/// Longest URL recognised (characters); longer runs are not links.
const MAX_URL: usize = 2048;

/// A URL found in text: the character range it covers and the URI to open.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FoundUrl {
    pub range: Range<usize>,
    pub uri: String,
}

fn is_url_char(c: char) -> bool {
    if c.is_alphanumeric() {
        return true;
    }
    matches!(
        c,
        '-' | '.'
            | '_'
            | '~'
            | ':'
            | '/'
            | '?'
            | '#'
            | '['
            | ']'
            | '@'
            | '!'
            | '$'
            | '&'
            | '('
            | ')'
            | '*'
            | '+'
            | ','
            | ';'
            | '='
            | '%'
    )
}

fn starts_with_at(text: &[char], at: usize, prefix: &str) -> bool {
    let mut i = at;
    for p in prefix.chars() {
        match text.get(i) {
            Some(c) if c.eq_ignore_ascii_case(&p) => i += 1,
            _ => return false,
        }
    }
    true
}

/// Finds the URLs in `text`, left to right, without overlaps.
pub fn find_urls(text: &[char]) -> Vec<FoundUrl> {
    let mut found = Vec::new();
    let mut i = 0;
    while i < text.len() {
        // Only at a word boundary: not inside "xhttp://" or "awww.".
        let boundary = i == 0 || !(text[i - 1].is_alphanumeric() || text[i - 1] == '/');
        let prefix = if boundary {
            PREFIXES.iter().find(|p| starts_with_at(text, i, p))
        } else {
            None
        };
        let Some(prefix) = prefix else {
            i += 1;
            continue;
        };
        let body_start = i + prefix.chars().count();
        let mut end = body_start;
        while end < text.len() && end - i < MAX_URL && is_url_char(text[end]) {
            end += 1;
        }
        end = trim_end(text, i, end);
        let body = &text[body_start..end.max(body_start)];
        let valid = match *prefix {
            "mailto:" => body.contains(&'@'),
            "www." => body.contains(&'.'),
            _ => body
                .first()
                .is_some_and(|c| c.is_alphanumeric() || *c == '['),
        };
        if end > body_start && valid && end - i < MAX_URL {
            let raw: String = text[i..end].iter().collect();
            let uri = if *prefix == "www." {
                format!("https://{raw}")
            } else {
                raw
            };
            found.push(FoundUrl { range: i..end, uri });
            i = end;
        } else {
            i += 1;
        }
    }
    found
}

/// Drops trailing sentence punctuation and unbalanced closing brackets.
fn trim_end(text: &[char], start: usize, mut end: usize) -> usize {
    while end > start {
        let last = text[end - 1];
        let unbalanced = |open: char, close: char| {
            let s = &text[start..end];
            s.iter().filter(|&&c| c == close).count() > s.iter().filter(|&&c| c == open).count()
        };
        let drop = TRAILING.contains(&last)
            || (last == ')' && unbalanced('(', ')'))
            || (last == ']' && unbalanced('[', ']'));
        if !drop {
            break;
        }
        end -= 1;
    }
    end
}

#[cfg(test)]
mod tests {
    use super::*;

    fn urls(s: &str) -> Vec<String> {
        let chars: Vec<char> = s.chars().collect();
        find_urls(&chars).into_iter().map(|u| u.uri).collect()
    }

    #[test]
    fn plain_urls_and_sentence_punctuation() {
        assert_eq!(
            urls("see https://ferrux.dev/docs."),
            ["https://ferrux.dev/docs"]
        );
        assert_eq!(
            urls("a http://x.example:8080/p?q=1&r=2#frag, b"),
            ["http://x.example:8080/p?q=1&r=2#frag"]
        );
        assert_eq!(urls("HTTPS://EXAMPLE.COM!"), ["HTTPS://EXAMPLE.COM"]);
        assert_eq!(
            urls("two: https://a.example and ftp://b.example/f"),
            ["https://a.example", "ftp://b.example/f"]
        );
    }

    #[test]
    fn brackets_are_kept_only_when_balanced() {
        assert_eq!(
            urls("(see https://en.wikipedia.org/wiki/Rust_(programming_language))"),
            ["https://en.wikipedia.org/wiki/Rust_(programming_language)"]
        );
        assert_eq!(
            urls("[docs](https://ferrux.dev/x)"),
            ["https://ferrux.dev/x"]
        );
        assert_eq!(urls("ipv6 http://[::1]:8000/ ok"), ["http://[::1]:8000/"]);
    }

    #[test]
    fn www_and_mailto() {
        assert_eq!(urls("go to www.ferrux.dev."), ["https://www.ferrux.dev"]);
        assert_eq!(
            urls("mail mailto:dev@ferrux.dev"),
            ["mailto:dev@ferrux.dev"]
        );
        assert!(urls("mailto:nobody").is_empty());
    }

    #[test]
    fn not_urls() {
        assert!(urls("no links here").is_empty());
        assert!(urls("xhttps://inside.word").is_empty());
        assert!(urls("https://").is_empty());
        assert!(urls("https://.").is_empty());
        assert!(urls("ferrux.dev").is_empty(), "no scheme, no www");
        assert!(urls("\"https://\"").is_empty());
    }

    #[test]
    fn quotes_and_angle_brackets_end_a_url() {
        assert_eq!(urls("<https://a.example/x>"), ["https://a.example/x"]);
        assert_eq!(urls("'https://a.example/y'"), ["https://a.example/y"]);
        assert_eq!(urls("\"https://a.example/z\""), ["https://a.example/z"]);
    }

    #[test]
    fn ranges_are_character_indices() {
        let chars: Vec<char> = "é https://a.example ü".chars().collect();
        let found = find_urls(&chars);
        assert_eq!(found[0].range, 2..19);
    }
}
