package logger

import (
	"log"
	"os"
)

type Logger struct {
	info  *log.Logger
	error *log.Logger
	debug *log.Logger
}

var L *Logger

func Init(level string) {
	L = &Logger{
		info:  log.New(os.Stdout, "[INFO]  ", log.LstdFlags|log.Lmicroseconds),
		error: log.New(os.Stderr, "[ERROR] ", log.LstdFlags|log.Lmicroseconds),
		debug: log.New(os.Stdout, "[DEBUG] ", log.LstdFlags|log.Lmicroseconds),
	}
}

func (l *Logger) Info(format string, v ...interface{}) {
	l.info.Printf(format, v...)
}

func (l *Logger) Error(format string, v ...interface{}) {
	l.error.Printf(format, v...)
}

func (l *Logger) Debug(format string, v ...interface{}) {
	l.debug.Printf(format, v...)
}

func (l *Logger) Warn(format string, v ...interface{}) {
	l.error.Printf("[WARN] "+format, v...)
}
