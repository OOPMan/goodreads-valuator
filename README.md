GoodReads Valuator v1.0.0
=========================

Contents
--------
1. Introduction
2. Installation
3. Usage


Introduction
------------

GoodReads Valuator is a simple command-line application that can be used
to calculate the value of a GoodReads shelf. 

Calculating the value involves loading all Reviews associated with a
Shelf from GoodReads and then searching pricing providers for the 
ISBNs associated with those reviews.

This tool was written to fulfill a personal need and thus the
pricing providers are currently limited to:

* Loot (http://www.loot.co.za)
* Amazon (https://www.amazon.com)


Installation
------------

Head over to https://github.com/OOPMan/goodreads-valuator/releases and
download the relevant installation binary for your platform.

In the event one is unavailable, you can download and extract the
universal zip file.


Usage
-----

Run `bin/goodreads-valuator --help` to print usage instructions.
