use std::io;
use thiserror::Error;

pub type Result<T> = std::result::Result<T, Error>;

#[derive(Debug, Error)]
pub enum Error {
    #[error("X11: {0}")]
    X11(String),
    #[error("encoder: {0}")]
    Encoder(String),
    #[error("{0}")]
    Msg(String),
    #[error(transparent)]
    Io(#[from] io::Error),
}

impl Error {
    pub fn x11(err: impl ToString) -> Self {
        Self::X11(err.to_string())
    }
}
