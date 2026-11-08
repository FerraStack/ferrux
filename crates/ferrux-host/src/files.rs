// SPDX-License-Identifier: Apache-2.0
//! File operations confined to `$HOME` (docs/API.md §6.5).
//!
//! Every path must resolve inside `$HOME` after following symlinks. The
//! final component is not followed by [`Files::stat`], [`Files::rename`] and
//! [`Files::delete`] (they act on a symlink itself); [`Files::open`] follows
//! it only if the target is inside `$HOME` too.
//!
//! This is a correctness rule, not a security boundary: a client that may
//! spawn programs can read any file Ferrux can (docs/API.md §3.1).

use std::fs;
use std::io;
use std::os::fd::OwnedFd;
use std::os::unix::fs::{MetadataExt, OpenOptionsExt};
use std::path::{Component, Path, PathBuf};
use std::time::UNIX_EPOCH;

use crate::{ErrorCode, HostError, Result};

/// Largest page [`Files::list`] returns.
pub const MAX_LIST_PAGE: usize = 1000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum FileType {
    File = 0,
    Dir = 1,
    Symlink = 2,
    Other = 3,
}

/// Mirrors the AIDL `FileStat`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileStat {
    pub path: PathBuf,
    pub name: String,
    pub file_type: FileType,
    pub size: u64,
    pub modified_millis: i64,
    pub mode: u32,
    pub symlink_target: Option<PathBuf>,
}

impl FileStat {
    fn from_metadata(path: PathBuf, meta: &fs::Metadata) -> Self {
        let ft = meta.file_type();
        let file_type = if ft.is_symlink() {
            FileType::Symlink
        } else if ft.is_dir() {
            FileType::Dir
        } else if ft.is_file() {
            FileType::File
        } else {
            FileType::Other
        };
        let modified_millis = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map_or(0, |d| d.as_millis() as i64);
        let symlink_target = ft.is_symlink().then(|| fs::read_link(&path).ok()).flatten();
        let name = path
            .file_name()
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_default();
        Self {
            name,
            file_type,
            size: meta.len(),
            modified_millis,
            mode: meta.mode() & 0o7777,
            symlink_target,
            path,
        }
    }
}

/// How [`Files::open`] opens a file; the strings of
/// `ParcelFileDescriptor.parseMode`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OpenMode {
    /// `"r"`
    Read,
    /// `"w"`, `"wt"`: create, truncate.
    Write,
    /// `"wa"`: create, append.
    Append,
    /// `"rw"`: create, keep contents.
    ReadWrite,
    /// `"rwt"`: create, truncate.
    ReadWriteTruncate,
}

impl OpenMode {
    pub fn parse(mode: &str) -> Result<Self> {
        Ok(match mode {
            "r" => Self::Read,
            "w" | "wt" => Self::Write,
            "wa" => Self::Append,
            "rw" => Self::ReadWrite,
            "rwt" => Self::ReadWriteTruncate,
            _ => {
                return Err(HostError::new(
                    ErrorCode::InvalidArgument,
                    format!("bad open mode {mode:?}"),
                ));
            }
        })
    }

    fn options(self) -> fs::OpenOptions {
        let mut o = fs::OpenOptions::new();
        match self {
            Self::Read => o.read(true),
            Self::Write => o.write(true).create(true).truncate(true),
            Self::Append => o.append(true).create(true),
            Self::ReadWrite => o.read(true).write(true).create(true),
            Self::ReadWriteTruncate => o.read(true).write(true).create(true).truncate(true),
        };
        o
    }
}

/// `$HOME`-confined file operations.
#[derive(Debug, Clone)]
pub struct Files {
    home: PathBuf,
}

fn outside(path: &str) -> HostError {
    HostError::new(
        ErrorCode::OutsideHome,
        format!("{path}: outside the home directory"),
    )
}

impl Files {
    pub fn new(home: impl Into<PathBuf>) -> Self {
        Self { home: home.into() }
    }

    /// The canonical home directory.
    fn home(&self) -> Result<PathBuf> {
        fs::canonicalize(&self.home).map_err(|e| HostError::io(&e, self.home.display()))
    }

    fn inside(&self, home: &Path, p: &Path) -> bool {
        p.starts_with(home)
    }

    /// Absolute form of `path` (relative paths, including `""`, are relative
    /// to `$HOME`).
    fn absolute(&self, path: &str) -> Result<PathBuf> {
        if path.contains('\0') {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("bad path {path:?}"),
            ));
        }
        Ok(self.home.join(path))
    }

    /// Resolves `path` without following its final component: the parent is
    /// canonicalised and must be inside `$HOME`. Returns `$HOME` itself for
    /// paths that name it.
    pub fn resolve_nofollow(&self, path: &str) -> Result<PathBuf> {
        let home = self.home()?;
        let abs = self.absolute(path)?;
        // Names `$HOME` itself (e.g. "", ".", "sub/.."), whose parent is
        // outside. A symlink that points at `$HOME` is not `$HOME`: it is a
        // file inside it, handled below.
        let final_is_symlink = fs::symlink_metadata(&abs).is_ok_and(|m| m.file_type().is_symlink());
        if !final_is_symlink && fs::canonicalize(&abs).is_ok_and(|p| p == home) {
            return Ok(home);
        }
        match abs.components().next_back() {
            Some(Component::Normal(name)) => {
                let parent = abs.parent().unwrap_or(Path::new("/"));
                let parent =
                    fs::canonicalize(parent).map_err(|e| HostError::io(&e, parent.display()))?;
                if !self.inside(&home, &parent) {
                    return Err(outside(path));
                }
                Ok(parent.join(name))
            }
            // "/", "..", "." endings: resolve the whole path.
            _ => self.resolve_follow(path),
        }
    }

    /// Resolves `path` following every symlink; it must exist and be inside
    /// `$HOME`.
    pub fn resolve_follow(&self, path: &str) -> Result<PathBuf> {
        let home = self.home()?;
        let abs = self.absolute(path)?;
        let real = fs::canonicalize(&abs).map_err(|e| HostError::io(&e, abs.display()))?;
        if !self.inside(&home, &real) {
            return Err(outside(path));
        }
        Ok(real)
    }

    /// Resolves an existing directory (e.g. a spawn `cwd`).
    pub fn resolve_dir(&self, path: &str) -> Result<PathBuf> {
        let dir = self.resolve_follow(path)?;
        if !dir.is_dir() {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("{path}: not a directory"),
            ));
        }
        Ok(dir)
    }

    /// `lstat`: describes a symlink itself.
    pub fn stat(&self, path: &str) -> Result<FileStat> {
        let p = self.resolve_nofollow(path)?;
        let meta = fs::symlink_metadata(&p).map_err(|e| HostError::io(&e, p.display()))?;
        Ok(FileStat::from_metadata(p, &meta))
    }

    /// One page of a directory, sorted by name. `limit` is capped at
    /// [`MAX_LIST_PAGE`].
    pub fn list(&self, path: &str, offset: usize, limit: usize) -> Result<Vec<FileStat>> {
        let dir = self.resolve_dir(path)?;
        let mut names: Vec<_> = fs::read_dir(&dir)
            .map_err(|e| HostError::io(&e, dir.display()))?
            .filter_map(|e| e.ok().map(|e| e.file_name()))
            .collect();
        names.sort();
        let limit = limit.min(MAX_LIST_PAGE);
        Ok(names
            .into_iter()
            .skip(offset)
            .take(limit)
            .filter_map(|name| {
                let p = dir.join(name);
                // Entries can vanish between readdir and lstat.
                fs::symlink_metadata(&p)
                    .ok()
                    .map(|m| FileStat::from_metadata(p, &m))
            })
            .collect())
    }

    /// Opens a regular file. A final symlink is followed only if its target
    /// is inside `$HOME`; new files get mode 0600. FIFOs, sockets and device
    /// nodes are refused, so a Binder thread can never block in `open`.
    pub fn open(&self, path: &str, mode: OpenMode) -> Result<OwnedFd> {
        let mut p = self.resolve_nofollow(path)?;
        if fs::symlink_metadata(&p).is_ok_and(|m| m.file_type().is_symlink()) {
            p = self.resolve_follow(path)?;
        }
        if p.is_dir() {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("{path}: is a directory"),
            ));
        }
        // O_NONBLOCK: opening a FIFO without a peer would block forever.
        let file = mode
            .options()
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_CLOEXEC | libc::O_NONBLOCK)
            .open(&p)
            .map_err(|e| HostError::io(&e, p.display()))?;
        let meta = file
            .metadata()
            .map_err(|e| HostError::io(&e, p.display()))?;
        if !meta.file_type().is_file() {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("{path}: not a regular file"),
            ));
        }
        let fd: OwnedFd = file.into();
        // Back to blocking I/O for the client.
        let flags =
            rustix::fs::fcntl_getfl(&fd).map_err(|e| HostError::io(&e.into(), p.display()))?;
        rustix::fs::fcntl_setfl(&fd, flags - rustix::fs::OFlags::NONBLOCK)
            .map_err(|e| HostError::io(&e.into(), p.display()))?;
        Ok(fd)
    }

    /// Sets the permission bits (`mode & 0o7777`) of a file or directory; a
    /// final symlink is followed if its target is inside `$HOME`.
    pub fn chmod(&self, path: &str, mode: u32) -> Result<()> {
        use std::os::unix::fs::PermissionsExt;
        let p = self.resolve_follow(path)?;
        fs::set_permissions(&p, fs::Permissions::from_mode(mode & 0o7777))
            .map_err(|e| HostError::io(&e, p.display()))
    }

    /// `mkdir -p`. Existing ancestors are canonicalised and must be inside
    /// `$HOME`; the missing tail may not contain `..`.
    pub fn mkdirs(&self, path: &str) -> Result<PathBuf> {
        let home = self.home()?;
        let abs = self.absolute(path)?;
        let mut existing = abs.as_path();
        let mut tail = Vec::new();
        while fs::symlink_metadata(existing).is_err() {
            match existing.components().next_back() {
                Some(Component::Normal(name)) => tail.push(name.to_owned()),
                _ => {
                    return Err(HostError::new(
                        ErrorCode::InvalidArgument,
                        format!("{path}: bad path"),
                    ));
                }
            }
            existing = existing.parent().unwrap_or(Path::new("/"));
        }
        let mut dir =
            fs::canonicalize(existing).map_err(|e| HostError::io(&e, existing.display()))?;
        if !self.inside(&home, &dir) {
            return Err(outside(path));
        }
        for name in tail.into_iter().rev() {
            dir.push(name);
            match fs::create_dir(&dir) {
                Ok(()) => {}
                Err(e) if e.kind() == io::ErrorKind::AlreadyExists => {}
                Err(e) => return Err(HostError::io(&e, dir.display())),
            }
        }
        if !dir.is_dir() {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                format!("{path}: not a directory"),
            ));
        }
        Ok(dir)
    }

    pub fn rename(&self, from: &str, to: &str) -> Result<()> {
        let src = self.resolve_nofollow(from)?;
        let dst = self.resolve_nofollow(to)?;
        let home = self.home()?;
        if src == home || dst == home {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                "cannot rename the home directory",
            ));
        }
        fs::rename(&src, &dst).map_err(|e| HostError::io(&e, format!("rename {from} → {to}")))
    }

    /// Deletes a file, symlink or (empty, or with `recursive` any) directory.
    /// Never deletes `$HOME` itself.
    pub fn delete(&self, path: &str, recursive: bool) -> Result<()> {
        let p = self.resolve_nofollow(path)?;
        if p == self.home()? {
            return Err(HostError::new(
                ErrorCode::InvalidArgument,
                "cannot delete the home directory",
            ));
        }
        let meta = fs::symlink_metadata(&p).map_err(|e| HostError::io(&e, p.display()))?;
        let r = if meta.is_dir() {
            if recursive {
                fs::remove_dir_all(&p)
            } else {
                fs::remove_dir(&p)
            }
        } else {
            fs::remove_file(&p)
        };
        r.map_err(|e| HostError::io(&e, p.display()))
    }
}
